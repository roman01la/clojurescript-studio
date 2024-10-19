(ns uix.playground.bus
  (:require [uix.playground.transit :as t]))

(goog-define RUNTIME false)

(def iframe (atom nil))

(def default-style
  (str "color: white; border: 1px solid black; border-radius: 2px;"))

(def styles
  (->> {:info "background: blue;"
        :bus "background: green;"
        :cljs-env "background: violet;"
        :editor "background: orange;"
        :api "background: purple;"
        :npm "background: red;"}
       (map (fn [[k v]] [k (str v default-style)]))
       (into {})))

(when-not ^boolean RUNTIME
  (set! js/window.__UIX_DEBUG_LOG__ goog/DEBUG))

(defn log [domain & args]
  (when (if ^boolean RUNTIME
          js/window.parent.__UIX_DEBUG_LOG__
          js/window.__UIX_DEBUG_LOG__)
    (apply js/console.log (str "%c" (name domain)) (styles domain :info) args)))

(defn- on-event+ [id]
  (js/Promise.
    (fn [resolve reject]
      (let [handler
            (fn handler [^js event]
              (let [event (.-data event)]
                (when (= "uix/response" (.-type event))
                  (let [[eid v] (t/read (.-data event))]
                    (when (= eid id)
                      (log :bus "on-event+" (if RUNTIME "runtime" "main") v)
                      (js/window.removeEventListener "message" handler)
                      (resolve v))))))]
        (js/window.addEventListener "message" handler)))))

(defn dispatch+ [event]
  (let [id (str (random-uuid))
        event #js {:type "uix/bus" :data (t/write [id event])}]
    (if ^boolean RUNTIME
      (.postMessage js/window.parent event "*")
      (do
        (.postMessage (.-contentWindow @iframe) event "*")
        (on-event+ id)))))
