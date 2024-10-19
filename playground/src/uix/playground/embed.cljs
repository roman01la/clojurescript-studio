(ns uix.playground.embed
  (:require [uix.playground.api :as api]
            [uix.playground.cljs :as pcljs]
            [uix.playground.config :as cfg]))

(def el (js/document.getElementById "progress-message"))

(def button (js/document.createElement "a"))
(set! (.-textContent button) "Open Sandbox")
(js/Object.assign (.-style button)
                  #js {:zIndex 9999999999999
                       :fontFamily "sans-serif"
                       :padding "8px 16px"
                       :cursor "pointer"
                       :fontSize "12px"
                       :position "fixed"
                       :bottom "16px"
                       :right "16px"
                       :backgroundColor "#18181b"
                       :color "#e4e4e7"
                       :borderRadius "5px"})

(defonce __init
  (let [id (-> js/window.location.href
               (.split "/")
               last)]
    (-> (js/Promise.all [(api/load-project id) (js/Promise. #(pcljs/init-runtime %1))])
        (.then (fn [[{:keys [files]} _]]
                 (set! (.-textContent el) "Initializing environment...")
                 (pcljs/eval-files-embed+
                   (->> (vals files)
                        (filter #(= "file" (:type %)))
                        (mapv #(select-keys % [:content :path])))
                   'playground.core)))
        (.then (fn [ret]
                 (set! (.-href button) (str cfg/APP_HOST "p/" id))
                 (.append js/document.body button)
                 (when (= :error/circular-deps ret)
                   (js/alert "Circular dependency detected, can't run the code"))))
        (.catch #(do
                   (js/console.error %)
                   (set! (.-textContent el) (or (.-message %) %)))))))
