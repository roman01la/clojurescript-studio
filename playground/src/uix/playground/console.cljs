(ns uix.playground.console
  (:require [console-feed :as cf]
            ["@heroicons/react/20/solid" :as icons]
            [uix.core :as uix :refer [defui $]]
            [uix.playground.bus :as bus]
            [uix.playground.ui :as ui]
            [uix.playground.macros :as m]))

(defui console-toolbar [{:keys [logs console? set-console clear-logs]}]
  (let [{:strs [log error warn]} (group-by #(.-method %) logs)]
    ($ :.bg-zinc-950.py-1.px-2.flex.justify-between
       {:style {:bottom "100%"}}
       ($ :.text-xs.px-1
          ($ :span.text-zinc-200
             "Console")
          ($ ui/tooltip {:label "Logs"}
             ($ :span.px-1.py-0.5.bg-zinc-800.m-1.rounded-sm.text-zinc-400
                (count log)))
          (when (seq warn)
            ($ ui/tooltip {:label "Warnings"}
               ($ :span.px-1.py-0.5.bg-amber-500.m-1.rounded-sm.text-zinc-50
                  (count warn))))
          (when (seq error)
            ($ ui/tooltip {:label "Errors"}
               ($ :span.px-1.py-0.5.bg-red-500.m-1.rounded-sm.text-zinc-50
                  (count error)))))
       ($ :.flex.gap-2
          ($ ui/tooltip {:label "Clear logs"}
             ($ :button.text-zinc-200.hover:bg-zinc-600.rounded-full
                {:title "Clear"}
                ($ icons/NoSymbolIcon {:class "w-4 h-4"
                                       :on-click clear-logs})))
          ($ ui/tooltip {:label "Toggle console"}
             ($ :button.text-zinc-200.hover:bg-zinc-600.rounded-sm
                ($ icons/ChevronDownIcon {:class ["w-4 h-4" (when-not console? "rotate-180")]
                                          :on-click #(set-console not)})))))))

(defn- obj->dom [v]
  (let [el (js/document.createElement (aget v "tagName"))]
    (doseq [x (js/Object.entries v)
            :let [k (aget x 0)]
            :when (not (identical? k "$$type"))]
      (case k
        "children"
        (doseq [x (aget x 1)]
          (.append el (obj->dom x)))

        "$$attrs"
        (doseq [x (js/Object.entries (aget x 1))]
          (.setAttribute el (aget x 0) (aget x 1)))

        (aset el k (aget x 1))))
    el))

(defn- maybe-convert [log]
  (let [v (aget (.-data log) 0)]
    (when (and (instance? (.. @bus/iframe -contentWindow -Object) v)
               (identical? (js/Symbol.for "DOM") (aget v "$$type")))
      (aset (.-data log) 0 (obj->dom v))))
  log)

(m/defmemo console [{:keys [on-toggle console? reloaded?]}]
  (let [[logs set-logs] (uix/use-state #js [])
        ref (uix/use-ref)]

    ;; hookup console UI with window.console object
    (uix/use-effect
      (fn []
        (when reloaded?
          (let [console (cf/Hook (.. @bus/iframe -contentWindow -console)
                                 (fn [log]
                                   (set-logs #(.concat % #js [(maybe-convert log)])))
                                 false)]
            #(cf/Unhook console))))
      [reloaded?])

    ;; scroll to new logs
    (uix/use-effect
      (fn []
        (when (pos? (.-length logs))
          (when-let [node @ref]
            (set! (.-scrollTop node) (.-scrollHeight node)))))
      [logs])

    ($ :.flex-1.bg-zinc-950.flex.flex-col
       ($ console-toolbar
          {:logs logs
           :console? console?
           :set-console on-toggle
           :clear-logs (uix/use-callback #(set-logs #js []) [])})
       ($ :.overflow-y-auto.flex-1
          {:ref ref}
          ($ cf/Console {:logs logs
                         :variant "dark"})))))
