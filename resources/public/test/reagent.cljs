(ns test.reagent
  (:require [reagent.core :as r]
            [reagent.dom :as dom]))

(defn app []
  (r/with-let [state (r/atom 0)]
    [:button#btn {:on-click #(swap! state inc)}
     @state]))

(dom/render [app] (js/document.getElementById "root-reagent"))

(let [el (js/document.getElementById "btn")]
  (.click el)
  (js/setTimeout
    #(js/console.assert (= "1" (.-textContent el)))
    0))
