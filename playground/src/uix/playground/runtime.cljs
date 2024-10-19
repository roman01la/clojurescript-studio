(ns uix.playground.runtime
  (:require [cljs.analyzer :as ana]
            [uix.playground.analyzer :as pana]
            [uix.playground.bus :as bus]
            [uix.playground.cljs :as pcljs]
            [uix.playground.transit :as t]))

(def element-keys ["tagName" "children"])

(defn- dom->obj [el]
  (let [ret #js {}
        attrs #js {}]
    (doseq [k element-keys]
      (aset ret k (aget el k)))
    (doseq [k (.getAttributeNames el)
            :let [attr (.getAttribute el k)]
            :when attr]
      (aset attrs k attr))
    (aset ret "$$attrs" attrs)
    (aset ret "$$type" (js/Symbol.for "DOM"))
    (aset ret "children" (-> (aget el "children") (js/Array.from) (.map #(dom->obj %))))
    ret))

(defonce __hack_console
  (let [log js/console.log]
    (set! js/console.log
          (fn [& args]
            (->> args
                 (map #(cond-> % (instance? js/Element %) (dom->obj)))
                 (apply log))))
    nil))

(def item-kinds
  {:fn 1
   :var 4
   :kw 17})

;; lookup used defs
(defn- uses->defs [uses]
  (->> uses
       (reduce-kv (fn [ret k v]
                    (assoc! ret k (or (get-in @pcljs/compile-state-ref [::ana/namespaces v :defs k])
                                      (get-in @pcljs/compile-state-ref [::ana/namespaces v :macros k]))))
                  (transient {}))
       persistent!))

;; ==== editor autocompletion ====

;; TODO: support multifile projects
(def ^:private make-suggestions
  (memoize
    (fn [a {:keys [use-macros uses] :as b}]
      (let [uses (uses->defs (into use-macros uses))]
        (into-array
          (for [[k {:keys [name doc arglists private fn-var macro]}]
                (into (apply merge (vals a))
                      (into uses (apply merge (vals (dissoc b :use-macros :uses)))))
                :when (not private)
                :let [k (str k)]]
            #js {:label k
                 :insertText k
                 :documentation doc
                 :detail (str name " " arglists)
                 :kind (cond
                         (or fn-var macro) (:fn item-kinds)
                         :else (:var item-kinds))}))))))

(def ^:private suggest-js-globals
  (memoize
    (fn []
      #js {:suggestions
           (into-array
             (for [k (js/Object.keys js/window)]
               #js {:label k
                    :insertText k
                    :kind (:var item-kinds)}))})))

(defn- suggest-keywords []
  (let [kws (into #{}
                  (comp (mapcat (comp :order ::ana/constants val))
                        (filter keyword?))
                  (::ana/namespaces @pcljs/compile-state-ref))
        suggestions (into-array
                      (for [k kws
                            :let [k (if (namespace k)
                                      (str (namespace k) "/" (name k))
                                      (name k))]]
                        #js {:label k
                             :insertText k
                             :kind (:kw item-kinds)}))]
    #js {:suggestions suggestions}))

(defn- suggest-ns-defs [word requires]
  (let [rns (requires (symbol (.replace word "/" "")))
        rns (get-in @pcljs/compile-state-ref [::ana/namespaces rns])]
    #js {:suggestions (make-suggestions {} (select-keys rns [:defs :macros]))}))

(defn- auto-completion [last-chars]
  (let [word (-> last-chars
                 (.replace "\t" "")
                 (.split " ")
                 last
                 (.replace "(" ""))
        core-syms (-> (get-in @pcljs/compile-state-ref [::ana/namespaces 'cljs.core])
                      (select-keys [:defs :macros]))]

    (if @pcljs/playground-ns
      (let [{:keys [requires] :as rns} (get-in @pcljs/compile-state-ref [::ana/namespaces @pcljs/playground-ns])]
        (cond
          ;; autocomplete keywords
          (.startsWith word ":")
          (suggest-keywords)

          ;; autocomplete js globals
          (= "js/" word)
          (suggest-js-globals)

          ;; autocomplete referenced ns defs + macros
          (.endsWith word "/")
          (suggest-ns-defs word requires)

          ;; autocomplete core defs + current ns defs + referred defs
          :else #js {:suggestions (make-suggestions core-syms (select-keys rns [:defs :uses :use-macros]))}))
      ;; autocomplete core defs
      #js {:suggestions (make-suggestions core-syms {})})))

(defn go-to-definition [files current-file line column]
  (pcljs/analyze-file (files current-file) current-file line column js/console.log)
  #_
  (let [ast (pana/analyze-file pcljs/compile-state-ref current-file (files current-file))]
    (js/console.log ast)))

;; ==== message bus ====
(defmulti on-message (fn [m _] m))

(defmethod on-message :runtime/init [_]
  (js/Promise.
    (fn [resolve reject]
      (pcljs/init-runtime resolve))))

(defmethod on-message :runtime/eval-one [_ code]
  (js/Promise.
    (fn [resolve reject]
      (pcljs/eval-cljs-one code resolve))))

(defmethod on-message :runtime/eval-files [_ files entry]
  (pcljs/eval-files+ files entry))

(defmethod on-message :runtime/eval-files-embed [_ files entry]
  (pcljs/eval-files-embed+ files entry))

(defmethod on-message :editor/auto-completion [_ last-chars]
  (auto-completion last-chars))

(defmethod on-message :editor/go-to-definition [_ files current-file line column]
  (go-to-definition files current-file line column))

(defmethod on-message :runtime/get-playground-ns-requires [_]
  (pcljs/get-playground-ns-requires))

(defmethod on-message :runtime/get-playground-ns [_]
  @pcljs/playground-ns)

(defmethod on-message :runtime/loaded [_]
  (js/console.log "RUNTIME LOADED"))

(defn- handle-event [^js event]
  (let [source (.-source event)
        origin (.-origin event)
        event (.-data event)]
    (when (= "uix/bus" (.-type event))
      (let [[id event] (t/read (.-data event))
            _ (bus/log :bus "runtime" event)
            v (apply on-message event)]
        (-> (if-not (instance? js/Promise v)
              (js/Promise.resolve v)
              v)
            (.then #(.postMessage source #js {:type "uix/response" :data (t/write [id %])} origin)))))))

(defonce __init_runtime
  (do
    (js/window.addEventListener "message" handle-event)
    (bus/dispatch+ [:runtime/loaded])
    nil))
