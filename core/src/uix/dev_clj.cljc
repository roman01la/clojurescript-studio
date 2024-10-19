(ns uix.dev-clj
  (:require [clojure.string :as str]
            [uix.linter]
            [uix.lib]))

(def ^:private goog-debug (with-meta 'goog.DEBUG {:tag 'boolean}))

(defn with-fast-refresh [var-sym fdecl]
  (let [signature `(when ~goog-debug
                     (when-let [f# (.-fast-refresh-signature ~var-sym)]
                       (f#)))
        maybe-conds (first fdecl)]
    (if (and (map? maybe-conds) (or (:pre maybe-conds) (:post maybe-conds)))
      (cons maybe-conds (cons signature (rest fdecl)))
      (cons signature fdecl))))

(defn- rewrite-form
  "Rewrites a form to replace generated names with stable names
  to make sure that hook's signature for fast-refresh does not update
  on every compilation, unless there was a change in the hook's body."
  [form]
  (clojure.walk/prewalk
    (fn [x]
      (cond
        (and (symbol? x) (re-matches #"^p\d*__\d+#$" (str x)))
        (symbol (str/replace (str x) #"__\d+#$" ""))

        :else x))
    form))

(defn- rewrite-forms [forms]
  (map rewrite-form forms))

(defn fast-refresh-signature [var-sym body]
  `(when ~goog-debug
     (when (cljs.core/exists? js/window.uix.dev)
       (let [sig# (js/window.uix.dev.signature!)]
         (sig# ~var-sym ~(str/join (rewrite-forms (uix.lib/find-form uix.linter/hook-call? body))) nil nil)
         (js/window.uix.dev.register! ~var-sym (.-displayName ~var-sym))
         (set! (.-fast-refresh-signature ~var-sym) sig#)))))
