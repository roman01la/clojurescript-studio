(ns test.promesa
  (:require [promesa.core :as p]))

(p/let [v (js/Promise.resolve 1)]
  (prn :promise v)
  (js/console.assert (= v 1)))
