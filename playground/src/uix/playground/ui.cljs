(ns uix.playground.ui
  (:require ["@radix-ui/react-tooltip" :as tooltip]
            ["@radix-ui/react-dialog" :as dialog]
            [uix.core :as uix :refer [defui $]]
            [uix.playground.hooks :as hooks]
            [uix.playground.webgl :as webgl]
            [uix.playground.macros :as m]))

(defui tooltip [{:keys [children label bg-color side] :or {bg-color "bg-zinc-800"}}]
  ($ tooltip/Root
    ($ tooltip/Trigger {:as-child true}
                       children)
    ($ tooltip/Portal
      ($ tooltip/Content
        {:side-offset 4
         :side (or side js/undefined)
         :style {:z-index 2}
         :class ["tooltip-content text-xs text-zinc-300 px-3 py-1 rounded-md shadow-md" bg-color]}
        label
        ($ tooltip/Arrow {:class "fill-zinc-800"})))))

(defui dialog [{:keys [default-open? open? title trigger children on-open-change animation overlay?]}]
  ($ dialog/Root
    {:default-open default-open?
     :open open?
     :on-open-change on-open-change}
    (when trigger
      ($ dialog/Trigger {:as-child true}
                        trigger))
    ($ dialog/Portal
      ($ dialog/Overlay {:style {:position "fixed"
                                 :inset 0
                                 :background-color (if overlay? "rgba(0, 0, 0, 0.6)" "transparent")}})
      ($ dialog/Content {:class ["bg-zinc-800 py-4 px-6 rounded-md shadow-lg fixed top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2
                                 text-zinc-300 border border-zinc-700 flex flex-col gap-4 w-96"
                                 animation]}
        #_($ dialog/Close {:as-child true}
                          ($ :button.absolute.top-4.right-4
                            ($ icons/XMarkIcon {:class "w-5 h-5"})))
        (when title
          ($ dialog/Title {:class "text-2xl font-semibold"}
            title))
        children
        ($ :.flex.justify-center
          ($ dialog/Close {:as-child true}
                          ($ :button
                            {:class "hover:text-lime-200 text-slate-100
                                           py-1 rounded-md border border-emerald-300 hover:border-emerald-200 shadow-md
                                           mt-4 relative text-center
                                           bg-gradient-to-bl from-emerald-400 to-lime-400
                                           hover:from-emerald-400 hover:to-lime-200 px-8"}
                            "Close")))))))

(def fragment-shader (webgl/read-shader "./shaders/blob.frag"))
(def vertex-shader (webgl/read-shader "./shaders/quad.vert"))

(def color-presets
  {:day [#js [0.18, 0.10, 0.4]
         #js [0.12, 0.10, 0.29]
         #js [0.095, 0.095, 0.1]]
   :evening [#js [0.18, 0.10, 0.4]
             #js [0.12, 0.10, 0.29]
             #js [0.12, 0.07, 0.1]]})

(def colors (:evening color-presets))

(defn render-fn [mouse-ref width height gl _ uniforms]
  (let [{:keys [u_time u_resolution u_color_1 u_color_2 u_color_accent u_blur u_mouse]} uniforms]
    (.viewport gl 0 0 width height)
    (u_time (js/performance.now))
    (u_resolution #js [width height])
    (u_color_1 (nth colors 0))
    (u_color_2 (nth colors 1))
    (u_color_accent (nth colors 2))
    (u_blur 0.6)
    (u_mouse @mouse-ref)))

(def uniforms
  {:u_time :1f
   :u_resolution :2fv
   :u_color_1 :3fv
   :u_color_2 :3fv
   :u_color_accent :3fv
   :u_blur :1f
   :u_mouse :2fv})

(m/defmemo canvas []
  (let [[[width height] set-size] (uix/use-state [0 0])
        mouse-ref (uix/use-ref #js [1 1])
        dpr (or 1 js/window.devicePixelRatio)
        dpr-width (* width dpr)
        dpr-height (* height dpr)
        [canvas set-canvas] (uix/use-state nil)]

    (hooks/use-event-listener
      js/window "resize"
      (uix/use-callback #(set-size [(.-innerWidth js/window) (.-innerHeight js/window)])
                        [])
      :initial? true)

    (hooks/use-event-listener
      js/window "mousemove"
      (uix/use-callback #(reset! mouse-ref #js [(.-clientX %) (.-clientY %)])
                        []))

    (webgl/use-webgl-program
      {:canvas canvas
       :fragment-shader fragment-shader
       :vertex-shader vertex-shader
       :uniforms uniforms}
      (partial render-fn mouse-ref dpr-width dpr-height))

    ($ :canvas.fixed
      {:ref set-canvas
       :width dpr-width
       :height dpr-height
       :style {:z-index -1}})))

(defui unavailable []
  ($ :<>
    ($ canvas)
    ($ :.text-center.flex.flex-col.gap-4.w-screen.h-screen.items-center.justify-center
      ($ :h1.text-5xl.font-semibold.bg-gradient-to-br.from-indigo-300.to-sky-100
        {:style {:background-clip :text
                 :-webkit-background-clip :text
                 :-webkit-text-fill-color :transparent
                 :line-height "64px"}}
        "Not available")
      ($ :h2.font-light.text-indigo-200 "ClojureScript Studio is not available in your location"))))
