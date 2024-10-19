(ns uix.playground.analyzer
  (:require [cljs.analyzer :as ana]
            [cljs.env :as env]
            [cljs.js :as cljs]
            [cljs.tagged-literals :as tags]
            [cljs.tools.reader :as r]
            [cljs.tools.reader.reader-types :as rt]
            [clojure.string :as str]))

(defn- drop-macros-suffix
  [ns-name]
  (when ns-name
    (if (str/ends-with? ns-name "$macros")
      (subs ns-name 0 (- (count ns-name) 7))
      ns-name)))

(defn- elide-macros-suffix
  [sym]
  (symbol (drop-macros-suffix (namespace sym)) (name sym)))

(defn- resolve-symbol
  [sym]
  (if (str/starts-with? (str sym) ".")
    sym
    (elide-macros-suffix (ana/resolve-symbol sym))))

(defn- alias-map
  [compiler cljs-ns]
  (->> (binding [env/*compiler* compiler]
         (ana/get-aliases cljs-ns))
       (remove (fn [[k v]] (symbol-identical? k v)))
       (into {})))

(defn- read [eof rdr]
  (binding [*ns* (symbol (drop-macros-suffix (str *ns*)))]
    (r/read {:eof eof :read-cond :allow :features #{:cljs}} rdr)))

(defn forms-seq [rdr]
  (let [eof #js {}
        forms-seq_ (fn forms-seq_ []
                     (lazy-seq
                       (let [form (read eof rdr)]
                         (when-not (identical? form eof)
                           (cons form (forms-seq_))))))]
    (forms-seq_)))

(defn parse-ns [source]
  (let [rdr (rt/indexing-push-back-reader source 1 nil)
        opts {}]
    (binding [env/*compiler* (env/default-compiler-env)
              r/*data-readers* tags/*cljs-data-readers*]
      (let [aenv (ana/empty-env)]
        (some
          (fn [form]
            (let [{:keys [op name] :as ast} (ana/analyze aenv form nil opts)]
              (when (= :ns op)
                ast)))
          (forms-seq rdr))))))

(defn analyze-file [compiler-ref filename source]
  (let [rdr (rt/indexing-push-back-reader source 1 filename)
        opts {:ns (:name (parse-ns source))}
        aenv (ana/empty-env)]
    (binding [env/*compiler* compiler-ref
              ana/*cljs-ns* (:ns opts)
              *ns* (create-ns (:ns opts))
              r/resolve-symbol resolve-symbol
              r/*alias-map* (alias-map compiler-ref (:ns opts))
              r/*data-readers* tags/*cljs-data-readers*]
      (doseq [form (forms-seq rdr)]
        (let [aenv (assoc aenv :ns (ana/get-namespace (:ns opts)))]
          (ana/analyze aenv form nil opts))))))

;;;; ============== analyze str ===================
(defn- trampoline-safe [f]
  (comp (constantly nil) f))

(defn- wrap-error [ex]
  {:error ex})

(defn- analyze-str* [bound-vars source name opts cb]
  (let [rdr (rt/indexing-push-back-reader source 1 name)
        cb (trampoline-safe cb)
        eof (js-obj)
        aenv (ana/empty-env)
        the-ns (or (:ns opts) 'cljs.user)
        bound-vars (merge bound-vars {:*cljs-ns* the-ns})
        asts (atom [])]
    (trampoline
      (fn analyze-loop [last-ast ns]
        (binding [env/*compiler* (:*compiler* bound-vars)
                  ana/*cljs-ns* ns
                  ana/*checked-arrays* (:checked-arrays opts)
                  ana/*cljs-static-fns* (:static-fns opts)
                  ana/*fn-invoke-direct* (and (:static-fns opts) (:fn-invoke-direct opts))
                  *ns* (create-ns ns)
                  ana/*passes* (:*passes* bound-vars)
                  r/*alias-map* (alias-map (:*compiler* bound-vars) ns)
                  r/*data-readers* (:*data-readers* bound-vars)
                  r/resolve-symbol resolve-symbol
                  ana/*cljs-file* (:cljs-file opts)]
          (when last-ast
            (swap! asts conj last-ast))
          (let [res (try
                      {:value (read eof rdr)}
                      (catch :default cause
                        (wrap-error
                          (ana/error aenv
                                     (str "Could not analyze " name) cause))))]
            (if (:error res)
              (cb res)
              (let [form (:value res)]
                (if-not (identical? eof form)
                  (let [aenv (cond-> (assoc aenv :ns (ana/get-namespace ana/*cljs-ns*))
                                     (:context opts) (assoc :context (:context opts))
                                     (:def-emits-var opts) (assoc :def-emits-var true))
                        res (try
                              {:value (ana/analyze aenv form nil opts)}
                              (catch :default cause
                                (wrap-error
                                  (ana/error aenv
                                             (str "Could not analyze " name) cause))))]
                    (if (:error res)
                      (cb res)
                      (let [ast (:value res)]
                        #_(if (#{:ns :ns*} (:op ast))
                            ((trampoline-safe #'cljs/ns-side-effects) bound-vars aenv ast opts
                             (fn [res]
                               (if (:error res)
                                 (cb res)
                                 (trampoline analyze-loop ast (:name ast))))))
                        #(analyze-loop ast ns))))
                  (cb {:value @asts}))))))) nil the-ns)
    @asts))

(defn analyze-str
  ([state source cb]
   (analyze-str state source nil cb))
  ([state source name cb]
   (analyze-str state source name nil cb))
  ([state source name opts cb]
   (analyze-str*
     {:*compiler* state
      :*data-readers* tags/*cljs-data-readers*
      :*passes* (or (:passes opts) ana/*passes*)
      :*analyze-deps* (:analyze-deps opts true)
      :*cljs-dep-set* ana/*cljs-dep-set*
      :*load-macros* (:load-macros opts true)
      :*load-fn* (or (:load opts) cljs/*load-fn*)
      :*eval-fn* (or (:eval opts) cljs/*eval-fn*)}
     source name opts cb)))
