(ns uix.hooks-test
  (:require [clojure.test :refer [deftest is testing run-tests async]]
            [uix.core :as uix]
            ["@testing-library/react" :as rtl]
            [uix.hooks.alpha :as hooks]))

(defn render-hook [f]
  (.. (rtl/renderHook f) -result -current))

(deftest test-with-return-value-check
  (testing "should return js/undefined when return value is not a function"
    (let [f (hooks/with-return-value-check (constantly 1))]
      (is (= js/undefined (f)))))
  (testing "when return value is a function, should return the function"
    (let [f (hooks/with-return-value-check (constantly identity))]
      (is (= identity (f))))))

(deftest test-use-state
  (let [[v f] (render-hook #(uix/use-state 0))]
    (is (zero? v))))

(deftest test-use-effect
  (let [v (render-hook (fn []
                         (let [[v f] (uix/use-state 0)]
                           (uix/use-effect #(f inc) [])
                           v)))]
    (is (= 1 v))))

(deftest test-clojure-primitives-identity-use-state
  (testing "UUID: should preserve identity"
    (let [uuid1 #uuid "b137e2ea-a419-464f-8da3-7159005afa35"
          uuid2 (render-hook (fn []
                               (let [[v f] (uix/use-state uuid1)]
                                 (uix/use-effect #(f #uuid "b137e2ea-a419-464f-8da3-7159005afa35") [])
                                 v)))]
      (is (identical? uuid1 uuid2))))
  (testing "Keyword: should preserve identity"
    (let [kw1 :hello/world
          kw2 (render-hook (fn []
                             (let [[v f] (uix/use-state kw1)]
                               (uix/use-effect #(f (constantly :hello/world)) [])
                               v)))]
      (is (identical? kw1 kw2))))
  (testing "Symbol: should preserve identity"
    (let [sym1 'hello-world
          sym2 (render-hook (fn []
                              (let [[v f] (uix/use-state sym1)]
                                (uix/use-effect #(f (constantly 'hello-world)) [])
                                v)))]
      (is (identical? sym1 sym2))))
  (testing "Map: should not preserve identity"
    (let [m1 {:hello 'world}
          m2 (render-hook (fn []
                            (let [[v f] (uix/use-state m1)]
                              (uix/use-effect #(f {:hello 'world}) [])
                              v)))]
      (is (not (identical? m1 m2))))))

(deftest test-clojure-primitives-identity-use-reducer
  (testing "UUID: should preserve identity"
    (let [uuid1 #uuid "b137e2ea-a419-464f-8da3-7159005afa35"
          uuid2 (render-hook (fn []
                               (let [[v f] (uix/use-reducer (fn [state action] action) uuid1)]
                                 (uix/use-effect #(f #uuid "b137e2ea-a419-464f-8da3-7159005afa35") [])
                                 v)))]
      (is (identical? uuid1 uuid2))))
  (testing "Keyword: should preserve identity"
    (let [kw1 :hello/world
          kw2 (render-hook (fn []
                             (let [[v f] (uix/use-reducer (fn [state action] action) kw1)]
                               (uix/use-effect #(f :hello/world) [])
                               v)))]
      (is (identical? kw1 kw2))))
  (testing "Symbol: should preserve identity"
    (let [sym1 'hello-world
          sym2 (render-hook (fn []
                              (let [[v f] (uix/use-reducer (fn [state action] action) sym1)]
                                (uix/use-effect #(f 'hello-world) [])
                                v)))]
      (is (identical? sym1 sym2))))
  (testing "Map: should not preserve identity"
    (let [m1 {:hello 'world}
          m2 (render-hook (fn []
                            (let [[v f] (uix/use-reducer (fn [state action] action) m1)]
                              (uix/use-effect #(f {:hello 'world}) [])
                              v)))]
      (is (not (identical? m1 m2))))))

(defn -main []
  (run-tests))

