(ns uix.core
  "Public API"
  (:refer-clojure :exclude [fn])
  #?(:cljs (:require-macros [uix.core :refer [fn]]))
  (:require [clojure.core :as core]
            [clojure.string :as str]
            [uix.compiler.aot]
            [uix.source]
            [cljs.core]
            [uix.linter]
            [uix.dev-clj]
            #?(:clj [uix.lib])
            [uix.hooks.alpha :as hooks]
            #?@(:cljs [[goog.object :as gobj]
                       [react]
                       [cljs-bean.core :as bean]
                       [uix.lib :refer [doseq-loop map->js]]])))

(def ^:private goog-debug (with-meta 'goog.DEBUG {:tag 'boolean}))

(defn- no-args-component [sym var-sym body]
  `(defn ~sym []
     (let [f# (core/fn [] ~@body)]
       (if ~goog-debug
         (binding [*current-component* ~var-sym] (f#))
         (f#)))))

(defn- with-args-component [sym var-sym args body]
  `(defn ~sym [props#]
     (let [clj-props# (glue-args props#)
           ~args (cljs.core/array clj-props#)
           f# (core/fn [] ~@body)]
       (if ~goog-debug
         (binding [*current-component* ~var-sym]
           (assert (or (map? clj-props#)
                       (nil? clj-props#))
                   (str "UIx component expects a map of props, but instead got " clj-props#))
           (f#))
         (f#)))))

(defn- no-args-fn-component [sym var-sym body]
  `(core/fn ~sym []
     (let [f# (core/fn [] ~@body)]
       (if ~goog-debug
         (binding [*current-component* ~var-sym] (f#))
         (f#)))))

(defn- with-args-fn-component [sym var-sym args body]
  `(core/fn ~sym [props#]
     (let [clj-props# (glue-args props#)
           ~args (cljs.core/array clj-props#)
           f# (core/fn [] ~@body)]
       (if ~goog-debug
         (binding [*current-component* ~var-sym]
           (assert (or (map? clj-props#)
                       (nil? clj-props#))
                   (str "UIx component expects a map of props, but instead got " clj-props#))
           (f#))
         (f#)))))

(defn- uix-sigs [fdecl]
  #_(assert-valid-fdecl fdecl)
  (core/let [asig
             (core/fn [fdecl]
               (core/let [arglist (first fdecl)
                          ;elide implicit macro args
                          arglist (if #?(:clj  (clojure.lang.Util/equals '&form (first arglist))
                                         :cljs (= '&form (first arglist)))
                                    #?(:clj  (clojure.lang.RT/subvec arglist 2 (clojure.lang.RT/count arglist))
                                       :cljs (subvec arglist 2 (count arglist)))
                                    arglist)
                          body (next fdecl)]
                 (if (map? (first body))
                   (if (next body)
                     (with-meta arglist (conj (if (meta arglist) (meta arglist) {}) (first body)))
                     arglist)
                   arglist)))]
    (if (seq? (first fdecl))
      (core/loop [ret [] fdecls fdecl]
        (if fdecls
          (recur (conj ret (asig (first fdecls))) (next fdecls))
          (seq ret)))
      (core/list (asig fdecl)))))

(defn parse-sig [form name fdecl]
  (let [[fname fdecl] (uix.lib/parse-sig name fdecl)]
    (uix.lib/assert!
     (= 1 (count fdecl))
     (str form " doesn't support multi-arity.\n"
          "If you meant to make props an optional argument, you can safely skip it and have a single-arity component.\n
                 It's safe to destructure the props value even if it's `nil`."))
    (let [[args & fdecl] (first fdecl)]
      (uix.lib/assert!
       (>= 1 (count args))
       (str form " is a single argument component taking a map of props, found: " args "\n"
            "If you meant to retrieve `children`, they are under `:children` field in props map."))
      [fname args fdecl])))

(defmacro
  ^{:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
                [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  defui
  "Creates UIx component. Similar to defn, but doesn't support multi arity.
  A component should have a single argument of props."
  [sym & fdecl]
  (let [[fname args fdecl] (parse-sig `defui sym fdecl)]
    (uix.linter/lint! sym fdecl &form &env)
    (if (uix.lib/cljs-env? &env)
      (let [var-sym (-> (str (-> &env :ns :name) "/" sym) symbol (with-meta {:tag 'js}))
            body (uix.dev-clj/with-fast-refresh var-sym fdecl)]
        `(do
           ~(if (empty? args)
              (no-args-component fname var-sym body)
              (with-args-component fname var-sym args body))
           (set! (.-uix-component? ~var-sym) true)
           (set! (.-displayName ~var-sym) ~(str var-sym))
           ~(uix.dev-clj/fast-refresh-signature var-sym body)))
      `(defn ~fname ~args
         ~@fdecl))))

(defmacro fn
  "Creates anonymous UIx component. Similar to fn, but doesn't support multi arity.
  A component should have a single argument of props."
  [& fdecl]
  (let [[sym fdecl] (if (symbol? (first fdecl))
                      [(first fdecl) (rest fdecl)]
                      [(gensym "uix-fn") fdecl])
        [fname args body] (parse-sig `fn sym fdecl)]
    (uix.linter/lint! sym body &form &env)
    (if (uix.lib/cljs-env? &env)
      (let [var-sym (with-meta sym {:tag 'js})]
        `(let [~var-sym ~(if (empty? args)
                           (no-args-fn-component fname var-sym body)
                           (with-args-fn-component fname var-sym args body))]
           (set! (.-uix-component? ~var-sym) true)
           (set! (.-displayName ~var-sym) ~(str var-sym))
           ~var-sym))
      `(core/fn ~fname ~args
         ~@fdecl))))

(defmacro source
  "Returns source string of UIx component"
  [sym]
  (uix.source/source &env sym))

(defmacro $
  "Creates React element

  DOM element: ($ :button#id.class {:on-click handle-click} \"click me\")
  React component: ($ title-bar {:title \"Title\"})"
  ([tag]
   (uix.linter/lint-element* &form &env)
   (uix.compiler.aot/compile-element [tag] {:env &env}))
  ([tag props & children]
   (uix.linter/lint-element* &form &env)
   (uix.compiler.aot/compile-element (into [tag props] children) {:env &env})))

(defn parse-defhook-sig [sym fdecl]
  (let [[fname fdecl] (uix.lib/parse-sig sym fdecl)]
    (uix.lib/assert! (str/starts-with? (name fname) "use-")
                     (str "React Hook name should start with `use-`, found `" (name fname) "` instead."))
    (uix.lib/assert!
      (= 1 (count fdecl))
      "uix.core/defhook should be single-arity function")
    [fname fdecl]))

(defmacro
  ^{:arglists '([name doc-string? attr-map? [params*] prepost-map? body]
                [name doc-string? attr-map? ([params*] prepost-map? body) + attr-map?])}
  defhook
  "Like `defn`, but creates React hook with additional validation,
  the name should start with `use-`
  (defhook use-in-viewport []
    ...)"
  [sym & fdecl]
  (let [[fname fdecl] (parse-defhook-sig sym fdecl)
        fname (vary-meta fname assoc :uix/hook true)]
    (uix.linter/lint! sym fdecl &form &env)
    `(defn ~fname ~@fdecl)))


;; === Error boundary ===

#?(:clj
    (defn create-error-boundary
      "Creates React's error boundary component

      display-name       — the name of the component to be displayed in stack trace
      derive-error-state — maps error object to component's state that is used in render-fn
      did-catch          — 2 arg function for side-effects, logging etc.
      receives the exception and additional component info as args
      render-fn          — takes state value returned from derive-error-state and a vector
      of arguments passed into error boundary"
      [{:keys [display-name derive-error-state did-catch]
        :or   {display-name (str (gensym "uix.error-boundary"))}}
       render-fn]
      ^::error-boundary {:display-name       display-name
                         :render-fn          render-fn
                         :did-catch          did-catch
                         :derive-error-state derive-error-state}))

;; === Hooks ===

(defn use-state [value]
  (hooks/use-state value))

(defn use-reducer
  ([f value]
   (hooks/use-reducer f value))
  ([f value init-state]
   (hooks/use-reducer f value init-state)))

#?(:clj
    (defn use-ref [value]
      (hooks/use-ref value)))

(defn use-context [value]
  (hooks/use-context value))

(defn use-debug
  ([v]
   (hooks/use-debug v))
  ([v fmt]
   (hooks/use-debug v fmt)))

(defn use-deferred-value [v]
  (hooks/use-deferred-value v))

(defn use-transition []
  (hooks/use-transition))

(defn use-id []
  (hooks/use-id))

(defn use-sync-external-store
  ([subscribe get-snapshot]
   (hooks/use-sync-external-store subscribe get-snapshot))
  ([subscribe get-snapshot get-server-snapshot]
   (hooks/use-sync-external-store subscribe get-snapshot get-server-snapshot)))

(defn vector->js-array [coll]
  (cond
    (vector? coll) `(jsfy-deps (cljs.core/array ~@coll))
    (some? coll) `(jsfy-deps ~coll)
    :else coll))

(defn- make-hook-with-deps [sym env form f deps]
  (when (uix.lib/cljs-env? env)
    (uix.linter/lint-exhaustive-deps! env form f deps))
  (if deps
    (if (uix.lib/cljs-env? env)
      `(~sym ~f ~(vector->js-array deps))
      `(~sym ~f ~deps))
    `(~sym ~f)))

(defmacro use-effect
  "Takes a function to be executed in an effect and optional vector of dependencies.

  See: https://reactjs.org/docs/hooks-reference.html#useeffect"
  ([f]
   (make-hook-with-deps 'uix.hooks.alpha/use-effect &env &form f nil))
  ([f deps]
   (make-hook-with-deps 'uix.hooks.alpha/use-effect &env &form f deps)))

(defmacro use-layout-effect
  "Takes a function to be executed in a layout effect and optional vector of dependencies.

  See: https://reactjs.org/docs/hooks-reference.html#uselayouteffect"
  ([f]
   (make-hook-with-deps 'uix.hooks.alpha/use-layout-effect &env &form f nil))
  ([f deps]
   (make-hook-with-deps 'uix.hooks.alpha/use-layout-effect &env &form f deps)))

(defmacro use-insertion-effect
  "Takes a function to be executed synchronously before all DOM mutations
  and optional vector of dependencies. Use this to inject styles into the DOM
  before reading layout in `useLayoutEffect`.

  See: https://reactjs.org/docs/hooks-reference.html#useinsertioneffect"
  ([f]
   (make-hook-with-deps 'uix.hooks.alpha/use-insertion-effect &env &form f nil))
  ([f deps]
   (make-hook-with-deps 'uix.hooks.alpha/use-insertion-effect &env &form f deps)))

(defmacro use-memo
  "Takes function f and required vector of dependencies, and returns memoized result of f.

   See: https://reactjs.org/docs/hooks-reference.html#usememo"
  [f deps]
  (make-hook-with-deps 'uix.hooks.alpha/use-memo &env &form f deps))

(defmacro use-callback
  "Takes function f and required vector of dependencies, and returns memoized f.

  See: https://reactjs.org/docs/hooks-reference.html#usecallback"
  [f deps]
  (make-hook-with-deps 'uix.hooks.alpha/use-callback &env &form f deps))

(defmacro use-imperative-handle
  "Customizes the instance value that is exposed to parent components when using ref.

  See: https://reactjs.org/docs/hooks-reference.html#useimperativehandle"
  ([ref f]
   (when (uix.lib/cljs-env? &env)
     (uix.linter/lint-exhaustive-deps! &env &form f nil))
   `(uix.hooks.alpha/use-imperative-handle ~ref ~f))
  ([ref f deps]
   (when (uix.lib/cljs-env? &env)
     (uix.linter/lint-exhaustive-deps! &env &form f deps))
   `(uix.hooks.alpha/use-imperative-handle ~ref ~f ~(vector->js-array deps))))


#?(:cljs
   (do
     (def ^:dynamic *current-component*)

     ;; React's top-level API

     (def ^:private built-in-static-method-names
       [:childContextTypes :contextTypes :contextType
        :getDerivedStateFromProps :getDerivedStateFromError])

     (defn create-class
       "Creates class based React component"
       [{:keys [constructor getInitialState render
                ;; lifecycle methods
                componentDidMount componentDidUpdate componentDidCatch
                shouldComponentUpdate getSnapshotBeforeUpdate componentWillUnmount
                ;; static methods
                childContextTypes contextTypes contextType
                getDerivedStateFromProps getDerivedStateFromError
                ;; class properties
                defaultProps displayName]
         :as fields}]
       (let [methods (map->js (apply dissoc fields :displayName :getInitialState :constructor :render
                                     built-in-static-method-names))
             static-methods (map->js (select-keys fields built-in-static-method-names))
             ctor (core/fn [props]
                    (this-as this
                      (.apply react/Component this (js-arguments))
                      (when constructor
                        (constructor this props))
                      (when getInitialState
                        (set! (.-state this) (getInitialState this)))
                      this))]
         (gobj/extend (.-prototype ctor) (.-prototype react/Component) methods)
         (when render (set! (.-render ^js (.-prototype ctor)) render))
         (gobj/extend ctor react/Component static-methods)
         (when displayName
           (set! (.-displayName ctor) displayName)
           (set! (.-cljs$lang$ctorStr ctor) displayName)
           (set! (.-cljs$lang$ctorPrWriter ctor)
                 (core/fn [this writer opt]
                   (-write writer displayName))))
         (set! (.-cljs$lang$type ctor) true)
         (set! (.. ctor -prototype -constructor) ctor)
         (set! (.-uix-component? ctor) true)
         ctor))

     (defn create-ref
       "Creates React's ref type object."
       []
       (react/createRef))

     (defn glue-args [^js props]
       (cond-> (.-argv props)
               (.-children props) (assoc :children (.-children props))))

     (defn- memo-compare-args [a b]
       (= (glue-args a) (glue-args b)))

     (defn memo
       "Takes component `f` and optional comparator function `should-update?`
       that takes previous and next props of the component.
       Returns memoized `f`.

       When `should-update?` is not provided uses default comparator
       that compares props with clojure.core/="
       ([f]
        (memo f memo-compare-args))
       ([^js f should-update?]
        (let [fm (react/memo f should-update?)]
          (when (.-uix-component? f)
            (set! (.-uix-component? fm) true))
          fm)))

     (defn use-ref
       "Takes optional initial value and returns React's ref hook wrapped in atom-like type."
       ([]
        (use-ref nil))
       ([value]
        (let [ref (hooks/use-ref nil)]
          (when (nil? (.-current ref))
            (set! (.-current ref)
                  (specify! #js {:current value}
                    IDeref
                    (-deref [this]
                      (.-current this))

                    IReset
                    (-reset! [this v]
                      (set! (.-current ^js this) v))

                    ISwap
                    (-swap!
                      ([this f]
                       (set! (.-current ^js this) (f (.-current ^js this))))
                      ([this f a]
                       (set! (.-current ^js this) (f (.-current ^js this) a)))
                      ([this f a b]
                       (set! (.-current ^js this) (f (.-current ^js this) a b)))
                      ([this f a b xs]
                       (set! (.-current ^js this) (apply f (.-current ^js this) a b xs)))))))
          (.-current ref))))

     (defn create-context
       "Creates React Context with an optional default value"
       ([]
        (react/createContext))
       ([default-value]
        (react/createContext default-value)))

     (defn start-transition
       "Marks updates in `f` as transitions
       See: https://reactjs.org/docs/react-api.html#starttransition"
       [f]
       (react/startTransition f))

     (defn as-react
       "Interop with React components. Takes UIx component function
       and returns same component wrapped into interop layer."
       [f]
       #(f #js {:argv (bean/bean %)}))

     (defn- stringify-clojure-primitives [v]
       (cond
         ;; fast direct lookup for a string value
         ;; already stored on the instance of the known type
         (keyword? v) (.-fqn v)
         (uuid? v) (.-uuid v)
         (symbol? v) (.-str v)
         :else v))

     (defn jsfy-deps [coll]
       (if (or (js/Array.isArray coll)
               (vector? coll))
         (reduce (core/fn [arr v]
                   (.push arr (stringify-clojure-primitives v))
                   arr)
                 #js []
                 coll)
         coll))

     (defn lazy
       "Like React.lazy, but supposed to be used with UIx components"
       [f]
       (let [lazy-component (react/lazy #(.then (f) (core/fn [component] #js {:default component})))]
         (set! (.-uix-component? lazy-component) true)
         lazy-component))

     (defn create-error-boundary
       "Creates React's error boundary component

       display-name       — the name of the component to be displayed in stack trace
       derive-error-state — maps error object to component's state that is used in render-fn
       did-catch          — 2 arg function for side-effects, logging etc.
       receives the exception and additional component info as args
       render-fn          — takes state value returned from error->state and a vector
       of arguments passed into error boundary"
       [{:keys [display-name derive-error-state did-catch]
         :or {display-name (str (gensym "uix.error-boundary"))}}
        render-fn]
       (let [constructor (core/fn [^js/React.Component this _]
                           (set! (.-state this) #js {:argv nil}))
             derive-state (core/fn [error]
                            #js {:argv (derive-error-state error)})
             render (core/fn []
                      (this-as this
                        (let [props (.-props this)
                              state (.-state this)
                              set-state (core/fn [new-value]
                                          (.setState this #js {:argv new-value}))]
                          (render-fn [(.-argv state) set-state]
                                     (glue-args props)))))
             class (create-class {:constructor constructor
                                  :displayName display-name
                                  :getDerivedStateFromError derive-state
                                  :componentDidCatch did-catch
                                  :render render})]
         (set! (.-uix-component? class) true)
         class))

     (defn forward-ref
       "Like React's `forwardRef`, but should be used only for UIx components
       when passing them into React components that inject a ref"
       [f]
       (let [ref-comp
             (react/forwardRef
               (core/fn [props ref]
                 (let [argv (cond-> (.-argv props)
                                    (.-children props) (assoc :children (.-children props))
                                    :always (assoc :ref ref))
                       argv (merge argv
                                   (-> (bean/bean props)
                                       (dissoc :argv :children)))]
                   (f argv))))]
         (set! (.-uix-component? ref-comp) true)
         ref-comp))

     (def suspense react/Suspense)
     (def strict-mode react/StrictMode)
     (def profiler react/Profiler)

     ;; SSR helpers
     (def client? (exists? js/document)) ;; cljs can run in a browser or Node.js
     (def server? (not client?))))
