(ns uix.playground.hooks
  (:require [uix.core :as uix]
            [react]))

(defn use-state [value]
  (let [[state set-state] (react/useState value)
        set-state (react/useCallback
                    (fn [& args]
                      (if (fn? (first args))
                        (let [f (first args)]
                          (set-state #(apply f % (rest args))))
                        (apply set-state args)))
                    #js [])]
    #js [state set-state]))

(defn use-event [f]
  (let [ref (uix/use-ref nil)]
    (cljs.core/reset! ref f)
    (uix/use-callback (fn [& args] (apply @ref args)) [])))

(defn use-state-with-log [value f]
  (let [[state set-state] (uix/use-state value)
        f (use-event f)
        set-state (uix/use-callback #(do (apply f %&)
                                         (apply set-state %&))
                                    [])]
    [state set-state]))

(defn use-memo-dep [dep]
  (let [ref (uix/use-ref nil)]
    (when (not= @ref dep)
      (reset! ref dep))
    @ref))

(defn use-event-listener [target event handler & {:keys [initial?]}]
  (uix/use-layout-effect
    (fn []
      (when initial? (handler))
      (.addEventListener target event handler)
      #(.removeEventListener target event handler))
    [target event handler initial?]))
