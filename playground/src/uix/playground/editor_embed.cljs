(ns uix.playground.editor-embed
  (:require [react-router-dom :as rrd]
            [uix.playground.api :as api]
            [uix.playground.bus :as bus]
            [uix.playground.config :as cfg]
            [uix.playground.editor :as editor]
            [uix.core :refer [defui $]]
            [uix.dom]
            [uix.playground.ui :as ui]))

(defui embed []
  ($ editor/project {:embedded? true}))

(def router
  (rrd/createBrowserRouter
    #js [#js {:path "/ee/:id"
              :element ($ embed)
              :loader (fn [^js data]
                        (let [id (.. data -params -id)]
                          (bus/log :info (str "Loading project \"" id "\"..."))
                          (-> (api/load-project id)
                              (.then (fn [data] (bus/log :info "Done.") data))
                              (.catch (fn [status]
                                        (throw (js/Response. nil #js {:status status})))))))}]))


(defonce __init_app
         (let [root (uix.dom/create-root (js/document.getElementById "root"))]
           (-> (js/fetch (str cfg/API_HOST "c"))
               (.then (fn [r]
                        (if (= 403 (.-status r))
                          (uix.dom/render-root ($ ui/unavailable) root)
                          (uix.dom/render-root ($ rrd/RouterProvider {:router router}) root)))))
           nil))
