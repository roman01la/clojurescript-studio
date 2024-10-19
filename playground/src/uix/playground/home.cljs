(ns uix.playground.home
  (:require ["@vercel/analytics/react" :as anal]
            [cljs-bean.core :as bean]
            [clojure.string :as str]
            [react-router-dom :as rrd]
            [react]
            [uix.core :refer [defui $] :as uix]
            [uix.dom]
            [uix.playground.api :as api]
            [uix.playground.bus :as bus]
            [uix.playground.config :as cfg]
            [uix.playground.ui :as ui]
            [uix.playground.home-shared :as home-shared]
            [shadow.lazy :as lazy]))

(defui root []
  ($ :<>
    ($ rrd/Outlet)
    ($ anal/Analytics)))

(defonce playground-project-loadable (lazy/loadable uix.playground.editor/project))
(defonce playground-project (uix/lazy #(lazy/load playground-project-loadable)))

(defui project []
  ($ react/Suspense {:fallback ($ ui/canvas)}
    ($ playground-project)))

(defui error-view []
  (let [error (bean/bean (rrd/useRouteError))]
    ($ :<>
      ($ ui/canvas)
      ($ :.text-center.flex.flex-col.gap-4.w-screen.h-screen.items-center.justify-center.text-indigo-200
        ($ :h1.text-5xl.font-semibold.bg-gradient-to-br.from-indigo-300.to-sky-100
          {:style {:background-clip :text
                   :-webkit-background-clip :text
                   :-webkit-text-fill-color :transparent
                   :line-height "64px"}}
          (:status error))
        ($ :h2.font-light
          "Huh, what happened?")
        ($ :a
          {:href "/"
           :class "font-medium hover:text-indigo-400"}
          "Start over")))))

(def router
  (rrd/createBrowserRouter
    #js [#js {:path "/"
              :element ($ root)
              :errorElement ($ error-view)
              :children #js [#js {:index true
                                  :element ($ home-shared/home)}
                             #js {:path "p/:id"
                                  :element ($ project)
                                  :loader (fn [^js data]
                                            (let [id (.. data -params -id)]
                                              (bus/log :info (str "Loading project \"" id "\"..."))
                                              (-> (api/load-project id)
                                                  (.then (fn [data] (bus/log :info "Done.") data))
                                                  (.catch (fn [status]
                                                            (throw (js/Response. nil #js {:status status})))))))}]}]))

(defonce __init_app
  (let [root (uix.dom/create-root (js/document.getElementById "root"))]
    (-> (js/fetch (str cfg/API_HOST "c"))
        (.then (fn [r]
                 (if (= 403 (.-status r))
                   (uix.dom/render-root ($ ui/unavailable) root)
                   (uix.dom/render-root ($ rrd/RouterProvider {:router router}) root)))))
    nil))
