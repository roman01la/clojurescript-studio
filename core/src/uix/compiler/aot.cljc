(ns uix.compiler.aot
  "Compiler code that translates HyperScript into React calls at compile-time."
  (:require [uix.compiler.js :as comp.js]
            [uix.compiler.attributes :as attrs]
            [cljs.analyzer :as ana]
            [uix.lib :refer [doseq-loop]]
            #?@(:cljs [[uix.compiler.attributes]
                       [uix.compiler.alpha :as uixc]
                       [uix.compiler.input]
                       [react :as react]])))

(defmulti compile-attrs
  "Compiles a map of attributes into JS object,
  or emits interpretation call for runtime, when a value
  at props position is dynamic (symbol)"
  (fn [tag attrs opts] tag))

(defmethod compile-attrs :element [_ attrs {:keys [tag-id-class]}]
  (if (or (map? attrs) (nil? attrs))
    `(cljs.core/array
      ~(cond-> attrs
         ;; interpret :style if it's not map literal
         (and (some? (:style attrs))
              (not (map? (:style attrs))))
         (assoc :style `(uix.compiler.attributes/convert-props ~(:style attrs) (cljs.core/array) true))
         ;; merge parsed id and class with attrs map
         :always (attrs/set-id-class tag-id-class)
         ;; camel-casify the map
         :always (attrs/compile-attrs {:custom-element? (last tag-id-class)})
         ;; emit JS object literal
         :always comp.js/to-js))
    ;; otherwise emit interpretation call
    `(uix.compiler.attributes/interpret-attrs ~attrs (cljs.core/array ~@tag-id-class) false)))

(defmethod compile-attrs :component [_ props _]
  (if (or (map? props) (nil? props))
    `(cljs.core/array ~props)
    `(uix.compiler.attributes/interpret-props ~props)))

(defmethod compile-attrs :fragment [_ attrs _]
  (if (map? attrs)
    `(cljs.core/array ~(-> attrs attrs/compile-attrs comp.js/to-js))
    `(uix.compiler.attributes/interpret-attrs ~attrs (cljs.core/array) false)))

(defn- input-component? [x]
  (contains? #{"input" "textarea"} x))

(defn- uix-element?
  "Returns true when `form` is `(uix.core/$ ...)`"
  [env form]
  (and (list? form)
       (symbol? (first form))
       (->> (first form) (ana/resolve-var env) :name (= 'uix.core/$))))

(def elements-list-fns
  '#{for map mapv filter filterv remove keep keep-indexed})

(defn- elements-list?
  "Returns true when `v` is form commonly used to render a list of elements
  `(map ...)`, `(for ...)`, etc"
  [v]
  (and (list? v)
       (symbol? (first v))
       (elements-list-fns (first v))))

(defn- normalize-element
  "When the second item in the element `v` is either UIx element or `elements-list?`,
  returns normalized element with empty map at props position
  and child element shifted into children position"
  [env v]
  (if (or (uix-element? env (second v))
          (elements-list? (second v)))
    (into [(first v) {}] (rest v))
    v))

(defmulti compile-element*
  "Compiles UIx elements into React.createElement"
  (fn [[tag] _]
    (cond
      (= :<> tag) :fragment
      (keyword? tag) :element
      (symbol? tag) :component
      (list? tag) :component)))

(defmethod compile-element* :default [[tag] _]
  (let [env (select-keys (meta tag) [:line :column])]
    (ana/warning ::incorrect-element-type env {})))

(defmethod ana/error-message ::incorrect-element-type [_ _]
  (str "Incorrect element type. UIx elements can be one of the following types:\n"
       "React Fragment: :<>\n"
       "Primitive element: keyword\n"
       "Component element: symbol"))

(defmethod compile-element* :element [v {:keys [env]}]
  (let [[tag attrs & children] (normalize-element env v)
        tag-id-class (attrs/parse-tag tag)
        attrs-children (compile-attrs :element attrs {:tag-id-class tag-id-class})
        tag-str (first tag-id-class)
        ret (if (input-component? tag-str)
              `(create-uix-input ~tag-str ~attrs-children (cljs.core/array ~@children))
              `(>el ~tag-str ~attrs-children (cljs.core/array ~@children)))]
    ret))

(defmethod compile-element* :component [v {:keys [env]}]
  (let [[tag props & children] (normalize-element env v)
        tag (vary-meta tag assoc :tag 'js)
        props-children (compile-attrs :component props nil)]
    `(uix.compiler.alpha/component-element ~tag ~props-children (cljs.core/array ~@children))))

(defmethod compile-element* :fragment [v _]
  (let [[_ attrs & children] v
        attrs (compile-attrs :fragment attrs nil)
        ret `(>el fragment ~attrs (cljs.core/array ~@children))]
    ret))

(defn compile-element [v {:keys [env] :as opts}]
  (if (uix.lib/cljs-env? env)
    (compile-element* v opts)
    v))

#?(:cljs
   (do
     (defn hiccup? [el]
       (when (vector? el)
         (let [tag (nth el 0 nil)]
           (or (keyword? tag)
               (symbol? tag)
               (fn? tag)
               (instance? MultiFn tag)))))

     (defn validate-children [children]
       (doseq-loop [child children]
         (cond
           (hiccup? child)
           (throw (js/Error. (str "Hiccup is not valid as UIx child (found: " child ").\n"
                                  "If you meant to render UIx element, use `$` macro, i.e. ($ " child ")\n"
                                  "If you meant to render Reagent element, wrap it with r/as-element, i.e. (r/as-element " child ")")))

           (sequential? child)
           (validate-children child)))
       true)

     (defn >el [tag attrs-children children]
       (let [args (.concat #js [tag] attrs-children)]
         (when ^boolean goog.DEBUG
           (validate-children args))
         (uixc/create-element args children)))

     (defn create-uix-input [tag attrs-children children]
       (if (uix.compiler.input/should-use-reagent-input?)
         (let [props (aget attrs-children 0)
               children (.concat #js [(aget attrs-children 1)] children)]
           (uixc/create-element #js [uix.compiler.input/reagent-input #js {:props props :tag tag}] children))
         (>el tag attrs-children children)))

     (def fragment react/Fragment)))
