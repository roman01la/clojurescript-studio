;; Compiling macro namespaces for bootstrapped
;; 1. cljs ns can :require-macros clj ns, but clj ns can't have any Clojure JVM or Java code
;; 2. same for cljc ns
(ns studio.test
  #?(:cljs (:require-macros [studio.test])))

(defmacro mult-10 [x]
  (* x 10))

(assert (= 70 (macroexpand-1 '(studio.test/mult-10 7))))
