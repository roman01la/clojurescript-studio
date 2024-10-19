(ns playground.default-uix
  (:require [uix.core :as uix :refer [defui $]]
            [uix.dom]))

(defui button [props]
  ($ :button.bg-sky-500.text-slate-50.rounded-full.w-10.h-10
    props))

(defui app []
  (let [[n set-n] (uix/use-state 0)]
    ($ :.flex.flex-col.justify-center.items-center.h-screen
      ($ :h1.text-3xl.font-semibold "Hello ClojureScript Studio")
      ($ :.p-6
        ($ button {:on-click #(set-n dec)} "-")
        ($ :span.mx-4.text-xl {} n)
        ($ button {:on-click #(set-n inc)} "+")))))

(defonce root (uix.dom/create-root (js/document.getElementById "root")))
(uix.dom/render-root ($ app) root)
