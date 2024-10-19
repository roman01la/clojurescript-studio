(ns uix.playground.sidebar
  (:require [uix.core :as uix :refer [$ defui]]
            [uix.playground.config :as cfg]
            [uix.playground.file-tree :as file-tree]
            [uix.playground.macros :as m]))

(defui sidebar-files [{:keys [tree set-tree set-current-file current-file]}]
  ($ :.p-2.flex.flex-col.w-full
     ;; TODO: file selection, dnd rearrangement, file upload, file tree export
     ($ :.flex.justify-between.border-b.border-b-zinc-700
       {:style {:height 33}}
       ($ :span
          "Files")
       #_($ :.flex.gap-2
            ($ file-tree/new-file-button {:tree tree :set-tree set-tree})
            ($ file-tree/new-folder-button {:tree tree :set-tree set-tree})))
     ($ file-tree/files-tree
        {:tree (tree "root")
         :set-tree set-tree
         :set-current-file set-current-file
         :current-file current-file})
     #_($ :.flex.flex-col.gap-1.grow.overflow-y-auto.pb-4
          (for [path files]
            ($ :button.flex.justify-between.text-left.hover:text-emerald-300.whitespace-nowrap
               {:key path}
               path)))))

(m/defmemo sidebar [{:keys [tree set-tree set-current-file current-file]}]
  ($ :.bg-zinc-800.text-zinc-300.text-sm.border-r.border-r-zinc-700.shadow-md.flex-1
     ($ sidebar-files
        {:tree tree
         :set-tree set-tree
         :set-current-file set-current-file
         :current-file current-file})
     ($ :.p-2.flex.flex-col.w-full
        ($ :.border-y.border-y-zinc-700.py-1.mb-1
           "Available libraries")
        ($ :.flex.flex-col.gap-1.grow
          (let [[clojure npm] (map #(sort-by pop %) cfg/local-deps)]
            ($ :<>
              ($ :.font-semibold.text-xs.mt-2.border-b.border-b-zinc-700.pb-1 "Clojars")
              (for [[name v] clojure]
                ($ :.flex.justify-between
                   {:key name}
                   ($ :span name)
                   ($ :span v)))
              ($ :.font-semibold.text-xs.mt-2.border-b.border-b-zinc-700.pb-1 "NPM")
              (for [[name v] npm]
                ($ :.flex.justify-between
                  {:key name}
                  ($ :span name)
                  ($ :span v)))))))))
