(ns uix.linters
  (:require
    [cljs.analyzer :as ana]
    [clojure.pprint :as pprint]
    [uix.linter :as linter]))

;; per element linting
(defmethod linter/lint-element :element/no-inline-styles [_ form env]
  (let [[_ tag props & children] form]
    (when (and (keyword? tag)
               (map? props)
               (contains? props :style))
      (linter/add-error! form :element/no-inline-styles (linter/form->loc (:style props))))))

(defmethod ana/error-message :element/no-inline-styles [_ _]
  "Inline styles are not allowed, put them into a CSS file instead")

;; Hooks linting
(defmethod linter/lint-hook-with-deps :hooks/too-many-lines [_ form env]
  (when (> (count (str form)) 180)
    (linter/add-error! form :hooks/too-many-lines (linter/form->loc form))))

(defmethod ana/error-message :hooks/too-many-lines [_ {:keys [source]}]
  (str "React hook is too large to be declared directly in component's body, consider extracting it into a custom hook:\n\n"
       (with-out-str (pprint/pprint `(~'defn ~'use-my-hook []
                                       ~source)))))

;; Components linting
(defmethod linter/lint-component :component/kebab-case-name [_ form env]
  (let [[_ sym] form]
    (when-not (re-matches #"[a-z-]+" (str sym))
      (linter/add-error! form :component/kebab-case-name (linter/form->loc sym)))))

(defmethod ana/error-message :component/kebab-case-name [_ _]
  "Component name should be in kebab case")
