(ns uix.playground.cljs
  (:require [cljs.analyzer :as ana]
            [cljs.env :as env]
            [cljs.js :as cljs]
            [clojure.set :as set]
            [clojure.string :as str]
            [shadow.cljs.bootstrap.browser :as boot]
            [shadow.cljs.bootstrap.env :as boot.env]
            [uix.playground.analyzer :as pana]
            [uix.playground.bus :as bus]
            [uix.playground.npm :as npm]
            [uix.playground.transit :as t]))

(when-not ^boolean goog/DEBUG
  (defonce __hack_require
    (let [get-internal js/goog.module.getInternal_]
      (set! js/goog.module.getInternal_
            (fn [name]
              (or (get-internal name)
                  (when (@boot.env/loaded-ref (symbol name))
                    (js/eval name)))))
      nil)))

;; hacking NPM deps require in bootstrapped
(defonce require-map (atom {}))

(defonce playground-ns (atom nil))

(defonce default-parse-ns (get-method ana/parse 'ns))

(defmethod ana/parse 'ns
  [op env form name opts]
  (let [form (clojure.walk/postwalk
               (fn [x]
                 (if (and (list? x) (= :require (first x)))
                   (map (fn [x]
                          (cond
                            ;; [react-dom] -> [node_modules$react_dom :as react-dom]
                            (and (vector? x)
                                 (contains? @require-map (str (first x)))
                                 (not (contains? (set x) :as))
                                 (symbol? (nth x 0)))
                            (-> x
                                (update 0 #(get @require-map (str %) %))
                                (conj :as (nth x 0)))

                            ;; [react-dom :as rdom] -> [node_modules$react_dom :as rdom]
                            (vector? x)
                            (update x 0 #(get @require-map (str %) %))

                            ;; react-dom -> [node_modules$react_dom :as react-dom]
                            (and (symbol? x)
                                 (contains? @require-map (str x)))
                            [(get @require-map (str x)) :as x]

                            :else x))
                        x)
                   x))
               form)]

    ;; store current ns for in-editor autocompletion
    (when (symbol? (second form))
      (reset! playground-ns (second form)))

    (default-parse-ns op env form name opts)))

;; editor integration
(def errors (atom #{}))

(defn add-error! [column line message filename]
  (when (and column line message)
    (swap! errors conj [column line message filename])))

;; report compiler warnings
(defn warning-handler [warning-type {:keys [column line] :as env} extra]
  (when (warning-type ana/*cljs-warnings*)
    (when-let [s (ana/error-message warning-type extra)]
      (add-error! column line (ana/message env s) (str "/root/" ana/*cljs-file*)))))

(defn report-error! [error filename]
  (let [{:clojure.error/keys [column line]} (ex-data (ex-cause error))]
    (add-error! column line (some-> error ex-cause ex-cause ex-message) filename)
    (js/console.error (or (some-> error ex-cause ex-cause ex-cause)
                          (some-> error ex-cause ex-cause)
                          (ex-cause error)
                          error))))

(defn send-errors! []
  (when (seq @errors)
    (bus/dispatch+ [:editor/set-errors @errors])
    (reset! errors #{})))

;; eval cljs
(defonce compile-state-ref (env/default-compiler-env))

(defn- ast->seq [ast]
  (tree-seq #(seq (:children %))
            #(->> ((apply juxt (:children %)) %)
                  (mapcat (fn [v]
                            (if (sequential? v)
                              v
                              [v]))))
            ast))

(defn analyze-str [source filename]
  (binding [ana/*cljs-warning-handlers* []
            ana/*cljs-warnings* {}]
    (pana/analyze-str compile-state-ref source filename {:load (partial boot/load compile-state-ref)} identity)))

(defn analyze-file [source filename line column cb]
  (let [asts (analyze-str source filename)]
    (->> (nth asts 2)
         ast->seq
         (filter #(= line (-> % :env :line)))
         (reduce
           (fn [ret ast]
             (if (-> ast :env :column (>= column))
               (reduced ast)
               ast))
           nil)
         (js/console.log))))

(defn get-playground-ns-requires []
  (let [alias-map (set/map-invert @require-map)]
    (into #{}
          (map #(str (alias-map % %)))
          (vals (get-in @compile-state-ref [::ana/namespaces @playground-ns :requires])))))

(defn- with-warnings []
  (merge ana/*cljs-warnings*
         {:uix.compiler.aot/incorrect-element-type true
          :uix.linter/missing-key true
          :uix.linter/hook-in-branch true
          :uix.linter/hook-in-loop true
          :uix.linter/non-reactive-re-frame-subscribe true
          :uix.linter/inline-function true
          :uix.linter/missing-deps true
          :uix.linter/deps-array-literal true
          :uix.linter/deps-coll-literal true
          :uix.linter/literal-value-in-deps true
          :uix.linter/unsafe-set-state true}))

(defn eval-cljs-one [source cb]
  (binding [ana/*cljs-warning-handlers* ana/default-warning-handler
            ana/*cljs-warnings* (with-warnings)]
    (cljs/eval-str
      compile-state-ref
      source
      "[playground]"
      {:eval cljs/js-eval
       :load (partial boot/load compile-state-ref)
       :*analyze-deps* false}
      cb)))

(defn- eval-cljs+ [source filename]
  (js/Promise.
    (fn [resolve reject]
      (binding [ana/*cljs-warning-handlers* [ana/default-warning-handler warning-handler]
                ana/*cljs-warnings* (with-warnings)]
        (cljs/eval-str
          compile-state-ref
          source
          filename
          {:eval cljs/js-eval
           :load (fn [rc cb]
                   (boot/load compile-state-ref rc cb))
           :cljs-file (str/replace filename #"^root/" "")
           :*analyze-deps* false}
          (fn [{:keys [error value] :as result}]
            (when error (report-error! error filename))
            (resolve)))))))

(defonce eval-cache (atom {}))

(defn- eval-cljs-memo+ [source filename force?]
  (if (and (not force?) (= source (:source (@eval-cache filename))))
    :eval/no
    (let [p (eval-cljs+ source filename)]
      (.then p (fn []
                 (swap! eval-cache assoc filename {:source source :result p})
                 :eval/yes)))))

(defn- eval-deps+ [ns files tree]
  (let [deps (tree ns)
        {:keys [content path]} (files ns)]
    (-> (js/Promise.all (map #(eval-deps+ % files tree) deps))
        (.then (fn [states]
                 (let [force? (some #{:eval/yes} states)]
                   (eval-cljs-memo+ content path force?)))))))

(defn walk-deps [ns tree seen path]
  (let [deps (tree ns)]
    (if (seen ns)
      (conj path ns)
      (let [seen (conj seen ns)
            path (conj path ns)]
        (mapcat #(walk-deps % tree seen path) deps)))))

(defn- get-eval-deps [files]
  (let [nss (map #(pana/parse-ns (:content %)) files)
        files (->> files
                   (map (fn [ns file]
                          [(:name ns) file])
                        nss)
                   (into {}))
        deps (reduce (fn [ret {:keys [name deps]}]
                       (assoc ret name deps))
                     {}
                     nss)]
    [files deps]))

(defn eval-files-embed+ [files entry]
  (let [[files deps] (get-eval-deps files)]
    (let [deps-path (walk-deps entry deps #{} [])]
      (if (seq deps-path)
        :error/circular-deps
        (eval-deps+ entry files deps)))))

(def before-load-hooks (atom #{}))
(def after-load-hooks (atom #{}))

(defn- eval-hook [s]
  (js/eval (str (munge (str/replace (str s) #"/" ".")) "();")))

(defn- hacky-load-hooks [files]
  (reset! before-load-hooks #{})
  (reset! after-load-hooks #{})
  (doseq [{:keys [content path]} files
          :let [asts (analyze-str content path)
                before-load (keep #(when (some-> % :var :info :dev/before-load) (:name %)) asts)
                after-load (keep #(when (some-> % :var :info :dev/after-load) (:name %)) asts)]]
    (swap! before-load-hooks into before-load)
    (swap! after-load-hooks into after-load))
  (run! eval-hook @before-load-hooks))

(defn eval-files+ [files* entry]
  (let [[files deps] (get-eval-deps files*)]
    (bus/dispatch+ [:editor/clear-errors])
    (let [deps-path (walk-deps entry deps #{} [])]
      (if (seq deps-path)
        (bus/dispatch+ [:editor/set-error {:type :circular-deps :path deps-path}])
        (do
          (hacky-load-hooks files*)
          (-> (eval-deps+ entry files deps)
              (.then #(do (run! eval-hook @after-load-hooks)
                          (send-errors!)))))))))

(def deps
  {'parinfer "3.13.1"
   'react-spring "9.7.2"})

(defn init-runtime [cb]
  (boot/init compile-state-ref
    {:path "/out/bootstrap"}
    (fn []
      (-> (js/fetch "/index.transit")
          (.then #(.text %))
          (.then #(do (reset! require-map (t/read %))
                      (cb))))
      #_
      (doseq [[module version] deps]
        (npm/add-source-package! module version))
      nil)))
