(ns playground.default-three-fiber
  (:require [uix.core :as uix :refer [defui $]]
            [uix.dom]
            ["@react-three/fiber" :as r3f]
            [three]))

(defui box [{:keys [position color]}]
  (let [ref (uix/use-ref)
        [active? set-active] (uix/use-state false)
        [hover? set-hover] (uix/use-state false)]

    (r3f/useFrame
      (fn [_ dt]
        (if active?
          (set! (.. @ref -rotation -y) (+ dt (.. @ref -rotation -y)))
          (set! (.. @ref -rotation -x) (+ dt (.. @ref -rotation -x))))))

    ($ :mesh
      {:position position
       :ref ref
       :scale (if active? 1.2 1)
       :on-click #(set-active not)
       :on-pointer-over #(set-hover true)
       :on-pointer-out #(set-hover false)}
      ($ :torusKnotGeometry {:args #js [0.6 0.3 128 16]})
      ($ :meshStandardMaterial {:color (if hover? "hotpink" color)}))))

(defui app []
  ($ :.h-screen
    ($ r3f/Canvas {:scene #js {:background (three/Color. "cadetblue")}}
      ($ :ambientLight {:intensity 0.7})
      ($ :pointLight {:position #js [0 2 4]})
      ($ box {:position #js [-1.2 0 0] :color "dodgerblue"})
      ($ box {:position #js [1.2 0 0] :color "orange"}))))

(defonce root (uix.dom/create-root (js/document.getElementById "root")))
(uix.dom/render-root ($ app) root)
