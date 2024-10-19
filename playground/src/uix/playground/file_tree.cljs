(ns uix.playground.file-tree
  (:require ["@heroicons/react/20/solid" :as icons]
            [clojure.string :as str]
            [uix.core :as uix :refer [defui $]]
            [uix.playground.bus :as bus]
            [uix.playground.config :as cfg]
            [uix.playground.context :as ctx]
            [uix.playground.hooks :as hooks]
            [uix.playground.ui :as ui]
            [uix.playground.file-tree-editor :as edit]))

(defui file-tree-button [{:keys [title on-click children]}]
  ($ ui/tooltip {:label title :bg-color "bg-zinc-700"}
    ($ :button.text-zinc-400.hover:text-zinc-100
      {:on-click on-click}
      children)))

(defui new-file-button [{:keys [tree set-tree]}]
  ($ file-tree-button
    {:title "New namespace"
     :on-click (fn []
                 (set-tree #(edit/add-tmp-file % tree)))}
    ($ icons/DocumentIcon {:class "w-4 h-4"})))

(defui new-folder-button [{:keys [tree set-tree]}]
  ($ file-tree-button
    {:title "New directory"
     :on-click (fn []
                 (set-tree #(edit/add-tmp-folder % tree)))}
    ($ icons/FolderIcon {:class "w-4 h-4"})))

(defui rename-file-button [{:keys [tree set-tree]}]
  ($ file-tree-button
    {:title "Rename"
     :on-click (fn [] (set-tree #(edit/set-file-renaming % tree)))}
    ($ icons/PencilIcon {:class "w-4 h-4"})))

(defui delete-file-button [{:keys [tree set-tree]}]
  (let [{:keys [set-current-file]} (uix/use-context ctx/editor)]
    ($ file-tree-button
      {:title "Delete"
       :on-click (fn []
                   (when (js/confirm (str "Are you sure you want to delete the " (:type tree) "? It will be deleted permanently."))
                     (set-tree #(edit/delete-file % tree))
                     (set-current-file cfg/DEFAULT_FILE)))}
      ($ icons/XMarkIcon {:class "w-5 h-5"}))))

(defui file-tree-text-field [{:keys [tree set-tree value]}]
  (let [type (:type tree)
        {:keys [set-current-file files]} (uix/use-context ctx/editor)
        on-change (fn [e]
                    (let [value (.. e -target -value)]
                      (cond
                        #_#_(and (= :file type)
                                 (not (str/blank? value))
                                 (not (str/ends-with? value ".cljs")))
                        (do (set! (.. e -target -value) "")
                            (js/alert "Only .cljs files are supported for now"))

                        ;; discard changes when a new name for existing entry is blank
                        (and (str/blank? value) (= :name/rename (:ui/state tree)))
                        (set-tree #(update % (:path tree) dissoc :ui/state))

                        ;; remove tmp entry when name was not provided
                        (str/blank? value) (set-tree #(dissoc % (:path tree)))

                        ;; just create a file
                        #_#_
                        (and (= :file type) (= 1 (count path)))
                        (-> tree
                            (z/edit assoc :name (str (first path) ".cljs"))
                            (z/edit dissoc :ui/state)
                            set-tree)

                        ;; rename file
                        (and (= "file" type) (= :name/rename (:ui/state tree)))
                        (set-tree #(edit/rename-file % tree value))

                        ;; rename folder
                        (and (= "folder" type) (= :name/rename (:ui/state tree)))
                        (set-tree #(edit/rename-folder % tree value))
                        
                        ;; add a folder
                        (and (= "folder" type) (= :name/not-set (:ui/state tree)))
                        (if (contains? files (edit/new-folder-path tree value))
                          (do (set! (.. e -target -value) "")
                              (js/alert "The directory already exists"))
                          (set-tree #(edit/add-folder % tree value)))

                        ;; add a namespace
                        ;; create file structure when a namespace is provided e.g. something.app.core
                        (and (= "file" type) (str/ends-with? (:path tree) "/*"))
                        (let [[_ [file]] (edit/new-file-name+files files tree value)]
                          (if-not file
                            (do (set! (.. e -target -value) "")
                                (js/alert "The namespace already exists"))
                            (do
                              (set-tree #(edit/add-namespace % tree value))
                              (set-current-file file)))))))]
    ($ :input.bg-zinc-600.rounded-sm.p-1.text-zinc-200.outline-0.w-full
      {:auto-focus true
       :placeholder (case type
                      "file" "Namespace"
                      "folder" "Folder name")
       :default-value value
       :on-blur on-change
       :on-key-down (fn [^js e]
                      (case (.-key e)
                        "Enter" (on-change e)
                        "Escape" (if (= :name/not-set (:ui/state tree))
                                   (set-tree #(dissoc % (:path tree))) ;; remove new unnamed file
                                   (set-tree #(update % (:path tree) dissoc :ui/state))) ;; discard renaming
                        nil))})))

(defui file-item [{:keys [tree set-tree depth set-current-file selected?]}]
  (let [{:keys [name type path]} tree]
    ($ :.flex.group
      ($ :button.w-full.text-left.flex.items-center.gap-2
        {:on-click (fn []
                     (case type
                       "folder" (set-tree #(update-in % [path :state/opened?] not))
                       "file" (set-current-file path)))}
        (case type
          "folder" (if (:state/opened? tree)
                     ($ icons/FolderOpenIcon {:class "w-4 h-4 text-sky-500"})
                     ($ icons/FolderIcon {:class "w-4 h-4 text-sky-500"}))
          "file" ($ icons/DocumentTextIcon {:class "w-4 h-4 text-emerald-500"}))
        ($ :span.group-hover:text-zinc-100
          {:class (when selected? "text-emerald-500")}
          name))
      ($ :.gap-2.hidden.group-hover:flex
        (when (= "folder" type)
          ($ :<>
            ($ new-file-button {:tree tree :set-tree set-tree})
            ($ new-folder-button {:tree tree :set-tree set-tree})))
        (when-not
          (or (and (= depth 1) (= "folder" type) (= "root/src" path))
              (and (= depth 2) (= "folder" type) (= "root/src/playground" path))
              (and (= depth 3) (= "file" type) (= "root/src/playground/core.cljs" path)))
          ($ :<>
            ($ rename-file-button {:tree tree :set-tree set-tree})
            ($ delete-file-button {:tree tree :set-tree set-tree})))))))

(defn- sorted-files-by-type [type files]
  (->> files
       (filter (comp #{type} :type))
       (sort-by :name)
       vec))

(defui files-tree [{:keys [tree set-tree set-current-file current-file depth] :or {depth 0}}]
  (let [{:keys [name children] :as node} tree
        children (uix/use-memo
                   #(let [children (vals children)
                          folders (sorted-files-by-type "folder" children)
                          files (sorted-files-by-type "file" children)]
                      (into folders files))
                   [children])]
    ($ :.text-zinc-400
      {:class (when (pos? depth) "mt-1")
       :style {:padding-left (when (> depth 1) (* 4 depth))}}
      (when (pos? depth)
        (cond
          ;; naming new file/folder
          (= :name/not-set (:ui/state node))
          ($ file-tree-text-field {:tree tree :set-tree set-tree})

          ;; renaming existing file/folder
          (= :name/rename (:ui/state node))
          ($ file-tree-text-field {:tree tree :set-tree set-tree :value name})

          ;; render file/folder item with toolbar buttons
          :else ($ file-item
                  {:tree tree
                   :set-tree set-tree
                   :depth depth
                   :selected? (= current-file (:path tree))
                   :set-current-file set-current-file})))
      (when (:state/opened? node)
        (for [ch children]
          ($ files-tree
            {:depth (inc depth)
             :key (or (:path ch) (str (:ui/state ch)))
             :tree ch
             :set-tree set-tree
             :set-current-file set-current-file
             :current-file current-file}))))))

(defn files->tree [files]
  (->> files
       ;; sort by depth to make assoc-in work properly (not overwrite stuff)
       (sort-by #(count (str/split (key %) #"/")))
       (reduce
         (fn [ret [k v]]
           (assoc-in ret (interpose :children (str/split k #"/")) v))
         {})))

(defn use-file-tree [files]
  (let [[files* set-files] (hooks/use-state-with-log files #(bus/log :editor "set files"))
        files* (hooks/use-memo-dep files*)
        ;; flat files into a tree
        tree (uix/use-memo #(files->tree files*) [files*])
        tree (hooks/use-memo-dep tree)]

    ;; sync with remote state

    (uix/use-effect
      #(set-files files)
      [files set-files])

    [tree set-files files*]))
