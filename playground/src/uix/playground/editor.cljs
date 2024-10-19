(ns uix.playground.editor
  (:require ["@monaco-editor/react" :default Editor]
            ["@radix-ui/react-tooltip" :as tooltip]
            [react-resizable-panels :as rrp]
            [react-router-dom :as rrd]
            ["@heroicons/react/20/solid" :as icons]
            [parinfer :as pinfer]
            [goog.functions :as gf]
            [clojure.string :as str]
            [uix.core :as uix :refer [defui $]]
            [cljfmt.core :as fmt]
            [cljs-bean.core :as bean]
            [uix.playground.api :as api]
            [uix.playground.macros :as m]
            [uix.playground.bus :as bus]
            [uix.playground.hooks :as hooks]
            [uix.playground.monaco :as pmonaco]
            [uix.playground.transit :as t]
            [uix.playground.ui :as ui]
            [uix.playground.file-tree :as file-tree]
            [uix.playground.console :as console]
            [uix.playground.sidebar :as sidebar]
            [uix.playground.top-bar :as top-bar]
            [uix.playground.config :as cfg]
            [uix.playground.context :as ctx]
            [uix.playground.coll :as coll]
            [cljs.pprint]))

(defmulti on-message (fn [m _] m))

(defmethod on-message :default [_])

(defmethod on-message :editor/clear-errors [_]
  (.removeAllMarkers (.-editor ^js (second @pmonaco/monaco-editor)) "error-source"))

(defmethod on-message :editor/set-errors [_ errors]
  (let [[^js editor ^js m] @pmonaco/monaco-editor
        models (->> (.getModels (.-editor m))
                    (reduce (fn [ret m]
                              (assoc ret (.. m -uri -path) m))
                            {}))
        markers (->> (group-by peek errors)
                     (map (fn [[filename errors]]
                            [filename
                             (for [[column line message _] errors]
                               #js {:severity (.. m -MarkerSeverity -Error)
                                    :startLineNumber line
                                    :startColumn column
                                    :endLineNumber line
                                    :endColumn column
                                    :message message})])))]
    (doseq [[filename markers] markers
            :let [model (models filename)]]
      (.setModelMarkers (.-editor m) model "error-source" (into-array markers)))))

(defmethod on-message :editor/set-error [_ error]
  (case (:type error)
    :circular-deps (let [message (str "Circular dependency " (str/join " -> " (:path error)))]
                     (js/console.error (str "Circular dependency " (str/join " -> " (:path error))))
                     (js/alert message))
    nil))

(defonce __init_bus
  (do (js/window.addEventListener "message"
         (fn [^js event]
           (let [event (.-data event)]
             (when (= "uix/bus" (.-type event))
               (let [[_ event] (t/read (.-data event))]
                 (bus/log :bus "main" event)
                 (apply on-message event))))))
      nil))

(defn- eval-cljs [files entry]
  (bus/dispatch+ [:runtime/eval-files files entry]))

(defn- eval-cljs-one [code]
  (bus/dispatch+ [:runtime/eval-one code]))

(defn select-eval-files [files]
  (->> files
       (keep (fn [[_ {:keys [type path] :as f}]]
               (when (and (= "file" type) (not (str/ends-with? path "/*")))
                 (select-keys f [:content :path]))))))

(defn eval-files [files]
  (eval-cljs
    (select-eval-files files)
    cfg/DEFAULT_NS))

(m/defmemo editor [{:keys [on-mount on-change font-size]}]
  ($ Editor {:height "100%"
             :width "100%"
             :language "clojure"
             :theme "oceanic-next"
             :options #js {:fontSize font-size
                           :autoIndent "keep"
                           :minimap #js {:enabled false}}
             :on-mount #(do (on-mount %1)
                            (reset! pmonaco/monaco-editor [%1 %2]))
             :on-change on-change}))

(m/defmemo output []
  ($ :iframe.flex-1.bg-zinc-50
    {:src "/sandbox.html"
     :ref (uix/use-callback #(reset! bus/iframe %) [])}))

(def fmt-indents
  '{reg-cofx [[:inner 0]]
    reg-event-db [[:inner 0]]
    reg-event-fx [[:inner 0]]
    reg-fx [[:inner 0]]
    reg-sub [[:inner 0]]
    defui [[:inner 0]]
    $ [[:inner 0]]})

(defn fmt-str [s]
  (fmt/reformat-string s {:indents (merge fmt/default-indents fmt-indents)}))

(defn monaco-set-value [^js editor-inst code & {:keys [undo-stop?] :or {undo-stop? true}}]
  (let [selection (.getSelection editor-inst)
        model (.getModel editor-inst)]
    (when undo-stop?
      (.pushStackElement model)) ;; push onto undo stack to undo the last edit before fmt
    (.pushEditOperations model
      #js [selection]
      #js [#js {:text code :range (.getFullModelRange model)}])))

(defn use-save-project [set-save-status]
  (let [{:keys [id]} (bean/bean (rrd/useParams))
        rev (rrd/useRevalidator)]
    (hooks/use-event
      (fn [project]
        (-> (api/save-project id project set-save-status)
            (.then #(.revalidate rev)))))))

(defn- run-parinfer! [^js editor-inst code skip?]
  (let [pos ^js (.getPosition editor-inst)
        column (.-column pos)
        line (.-lineNumber pos)
        result (pinfer/indentMode code #js {:cursorLine line :cursorX column})]
    (when (.-success result)
      (let [fmt-code (.-text result)]
        (when (not= fmt-code code)
          (reset! skip? true)
          (monaco-set-value editor-inst fmt-code :undo-stop? false)
          (.setPosition editor-inst #js {:column (inc (.-cursorX result)) :lineNumber (.-cursorLine result)}))))))

(defn use-save-handler [id ^js editor-inst project files set-tree current-file save-status set-save-status embedded?]
  (let [navigate (rrd/useNavigate)
        current-file-saved-content (get-in (:files project) [current-file :content])
        skip? (uix/use-ref false)
        on-editor-change* (uix/use-memo
                            (fn []
                              (gf/debounce
                                (fn [code ^js dt]
                                  (when-not (.-isFlush dt)
                                    (bus/log :editor "on-change-debounced")
                                    (set-tree #(assoc-in % [current-file :content] code))))
                                1000))
                            [set-tree current-file])
        on-editor-change (hooks/use-event
                           (fn [code ^js dt]
                             #_(if-not @skip?
                                 (run-parinfer! editor-inst code skip?))
                             (do
                               #_(reset! skip? false)
                               ;; set idle status when editor content is same as saved content
                               (if (= code current-file-saved-content)
                                 (set-save-status :idle)
                                 (do
                                   ;; set file save status to pending if not set yet
                                   (when (not= :pending (:save-status (files current-file)))
                                     (set-save-status :pending current-file))
                                   ;; set global save status to pending if not set yet
                                   (when (not= :pending save-status)
                                     (set-save-status :pending))))
                               (on-editor-change* code dt))))
        save-project* (use-save-project set-save-status)
        save-project (hooks/use-event
                       (fn []
                         (let [token (js/localStorage.getItem (str "uixp/" id))
                               #_#_files (->> files
                                              (map (fn [[k v]] [k (assoc v :id (str (random-uuid)))]))
                                              (into {}))]
                           (cond
                             ;; when is owner and code changed
                             (and token (= :pending save-status))
                             (let [code (fmt-str (.getValue (.getModel editor-inst)))
                                   files (assoc-in files [current-file :content] code)]
                               (bus/log :editor "saving owned project")
                               (monaco-set-value editor-inst code)
                               (save-project* {:files files}))

                             ;; when not an owner
                             (not token)
                             (do (bus/log :editor "saving (forking) someone's project")
                                 (api/create-project navigate files set-save-status))))))]
    (hooks/use-event-listener
      js/window "keydown"
      (hooks/use-event
        (fn [^js ev]
          (when-not embedded?
            (when (and editor-inst (= "s" (.-key ev)) (.-metaKey ev))
              (.preventDefault ev)
              (save-project))))))

    [save-status on-editor-change save-project save-project*]))

(defn use-init-cljs-env [editor-inst eval-cljs]
  (let [[ready? set-ready] (uix/use-state false)
        [reloading set-reloading] (uix/use-state :idle)
        reloading? (= :reloading reloading)
        reloaded? (= :idle reloading)]

    ;; needed for hot-reloading in dev
    (when ^boolean goog/DEBUG
      (uix/use-memo #(set-ready false) []))

    ;; reload the sandbox
    (uix/use-effect
      (fn []
        (when reloading?
          (bus/log :info "reloading the sandbox")
          (.reload (.. @bus/iframe -contentWindow -location))))
      [reloading?])

    ;; cljs env initialized
    (uix/use-effect
      (fn []
        (bus/log :cljs-env "Initializing ClojureScript environment...")
        (defmethod on-message :runtime/loaded [_]
          (-> (bus/dispatch+ [:runtime/init])
              (.then (fn []
                       (bus/log :cljs-env "cljs env ready")
                       (set-reloading #(if (= :reloading %) :done %))
                       (set-ready true))))))
      [])

    ;; eval after reload
    (uix/use-effect
      (fn []
        (when (and editor-inst (= :done reloading))
          (bus/log :cljs-env "eval after reload")
          (eval-cljs)
          (set-reloading :idle)))
      [reloading editor-inst eval-cljs])

    [ready? reloaded? set-reloading]))

(m/defmemo browser-tool-bar [{:keys [id on-reload]}]
  ($ :.px-2.py-2.bg-zinc-950.text-zinc-200.flex.gap-2
    #_#_
    ($ ui/tooltip {:label "Go back"}
      ($ :button.hover:bg-zinc-600.rounded-sm
        {:on-click js/console.log}
        ($ icons/ChevronLeftIcon {:class "w-4 h-4"})))
    ($ ui/tooltip {:label "Go forward"}
      ($ :button.hover:bg-zinc-600.rounded-sm
        {:on-click js/console.log}
        ($ icons/ChevronRightIcon {:class "w-4 h-4"})))
    ($ ui/tooltip {:label "Reload" :side "bottom"}
      ($ :button.hover:bg-zinc-600.rounded-sm.px-1.group
        {:on-click #(on-reload)}
        ($ icons/ArrowPathIcon {:class "w-4 h-4 transition-transform group-hover:rotate-45"})))
    ($ ui/tooltip {:label "Copy link to preview page" :side "bottom"}
      ($ :input.bg-zinc-600.px-3.py-1.rounded-sm.text-xs.font-normal.text-zinc-400.flex-1
        {:read-only true
         :value (str cfg/APP_HOST "e/" id)
         :style {:outline :none}
         :on-click (fn [^js e]
                     #_(.select (.-target e))
                     #_(copy-link (str HOST "e/" id) "Embed link copied!"))}))))


(defui resize-handler [{:keys [direction] :or {direction "horizontal"}}]
  ($ :.relative
    {:style {:z-index 1}}
    (cond
      (= direction "horizontal")
      ($ rrp/PanelResizeHandle {:class "absolute h-full w-2 hover:bg-zinc-300"})

      (= direction "vertical")
      ($ rrp/PanelResizeHandle {:class "absolute w-full h-2 bottom-0 hover:bg-zinc-300"}))))

(defn use-collapsible-panel [min-size max-size expanded?*]
  (let [pane-ref (uix/use-ref)
        pane-size (uix/use-ref max-size)
        [expanded? set-expanded] (uix/use-state true)]
    (uix/use-effect
      (fn []
        (set-expanded expanded?*))
      [expanded?*])
    (uix/use-effect
      (fn []
        (if expanded?
          (when @pane-size
            (bus/log :info "set panel expanded" expanded? @pane-size)
            (.resize @pane-ref @pane-size "pixels"))
          (do
            (bus/log :info "set panel expanded" expanded?)
            (when-not max-size
              (reset! pane-size (.getSize @pane-ref "pixels")))
            (.resize @pane-ref min-size "pixels"))))
      [expanded? min-size max-size])
    [pane-ref expanded? set-expanded]))

(defn use-manage-preview [ready? preview? set-reloading]
  (let [panel-ref (uix/use-ref)
        mounted? (uix/use-ref)]
    (uix/use-effect
      (fn []
        ;; actually toggle preview
        (when (and ready? @mounted?)
          (bus/log :info "set preview open" preview?)
          (if preview?
            (.expand @panel-ref)
            (.collapse @panel-ref)))

        ;; reload when toggling preview
        (when (and preview? ready?)
          (when @mounted?
            (bus/log :info "reload preview after toggling")
            (set-reloading :reloading))
          (reset! mounted? true)))
      [preview? ready? set-reloading])
    panel-ref))

(defn use-responsive-editor-layout [enabled?]
  (let [[panes-direction set-panes-direction] (uix/use-state "horizontal")]
  ;; update panes layout on small screens
    (uix/use-layout-effect
      (fn []
        (when enabled?
          (let [mq (js/window.matchMedia "(max-width: 1024px)")
                _ (set-panes-direction (if (.-matches mq) "vertical" "horizontal"))
                handler (fn [^js e]
                          (set-panes-direction (if (.-matches e) "vertical" "horizontal")))]
            (.addListener mq handler)
            #(.removeListener mq handler))))
      [enabled?])
    panes-direction))

(def pending-save-icon
  ($ :.w-4.h-4.p-1
    ($ :svg {:view-box "0 0 8 8"}
      ($ :circle
        {:cx 4
         :cy 4
         :r 4
         :fill "currentColor"}))))

(defui tab-button [{:keys [filename selected? persistent? pending? on-select on-persist on-close]}]
  (let [label (uix/use-memo
                (fn []
                  (let [segs (drop 2 (str/split filename #"/"))
                        chs (->> (butlast segs) (mapv first))
                        label (->> (str/replace (last segs) ".cljs" "")
                                   (conj chs)
                                   (str/join "."))]
                    label))
                [filename])]
    ($ :button.text-zinc-300.text-xs.py-3.px-2.bg-zinc-800.flex.gap-2.border-r.border-r-zinc-700
      {:class [(when selected? "border-b border-b-emerald-500")
               (when-not persistent? "italic")]
       :on-click #(on-select filename)
       :on-double-click on-persist}
      ($ icons/DocumentTextIcon {:class "w-4 h-4 text-emerald-500"})
      label
      (cond
        pending? pending-save-icon
        (not on-close) ($ :.w-4.h-4)
        :else ($ :.transition-transform.hover:scale-125
                {:on-click #(do
                              (.stopPropagation %)
                              (on-close filename))}
                ($ icons/XMarkIcon {:class "w-4 h-4"}))))))

(m/defmemo tab-bar [{:keys [set-current-file current-file embedded?]}]
  (let [[tabs set-tabs] (uix/use-state [])
        tabs (hooks/use-memo-dep tabs)
        {:keys [files set-sidebar]} (uix/use-context ctx/editor)
        on-close (hooks/use-event
                   (fn [filename]
                     (let [idx (.indexOf ^PersistentVector (mapv :name tabs) filename)
                           tabs (vec (remove (comp #{filename} :name) tabs))
                           nidx (min
                                  (max (dec idx) 0)
                                  idx
                                  (count tabs))
                           next-file (:name (nth tabs nidx))]
                       (set-current-file next-file)
                       (set-tabs tabs))))]
    (uix/use-effect
      (fn []
        (let [has-tab? (some #(= current-file (:name %)) tabs)
              pidx (coll/find-index (comp not :persistent?) tabs)]
          (when-not has-tab?
            (if pidx
              (set-tabs (assoc tabs pidx {:name current-file :persistent? false}))
              (set-tabs #(conj % {:name current-file :persistent? false}))))))
      [tabs current-file])

    (uix/use-effect
      (fn []
        (when (seq tabs)
          (when-let [idx (coll/find-index (comp not :persistent?) tabs)]
            (let [tab (tabs idx)
                  file (files (:name tab))]
              (when (= :pending (:save-status file))
                (bus/log :editor "set tab persistent" (:name tab))
                (set-tabs #(assoc-in % [idx :persistent?] true)))))))
      [tabs files])

    ($ :.flex
      (when embedded?
        ($ top-bar/toggle-btn {:set-sidebar set-sidebar :class "mx-4"}))
      (for [{:keys [name persistent?] :as tab} tabs]
        (let [idx (.indexOf tabs tab)]
          ($ tab-button
            {:key name
             :filename name
             :selected? (= name current-file)
             :persistent? persistent?
             :pending? (= :pending (:save-status (files name)))
             :on-select set-current-file
             :on-persist (fn [] (set-tabs #(assoc-in % [idx :persistent?] true)))
             :on-close (when (> (count tabs) 1) on-close)}))))))

(defn- get-panes-sizes-from-url []
  (map
    #(js/parseInt % 10)
    (-> (js/URL. js/window.location.href)
        .-searchParams
        (.get "p")
        (or "60_40")
        (str/split #"_"))))

(let [[editor preview] (get-panes-sizes-from-url)]
  (def panels-cfg
    {:editor {:editor 60
              :preview 40
              :console? true
              :browser-bar? true
              :height "calc(100vh - 40px - 24px)"}
     :embed {:editor editor
             :preview preview
             :console? false
             :browser-bar? false
             :height "100vh"}}))

(defui editor-layout
  [{:keys [id set-editor on-editor-change preview?
           set-reloading ready? reloaded?
           set-current-file current-file
           sidebar? tree set-tree
           embedded?]}]
  (let [[params] (rrd/useSearchParams)
        config (get panels-cfg (if embedded? :embed :editor))
        panes-direction (use-responsive-editor-layout (not embedded?))
        [console-pane-ref console-expanded? set-console-expanded] (use-collapsible-panel
                                                                    24 nil (if-let [p (.get params "console")]
                                                                             (= p "1")
                                                                             (:console? config)))
        [sidebar-pane-ref] (use-collapsible-panel 0 260 sidebar?)
        preview-panel-ref (use-manage-preview ready? preview? set-reloading)]
    ($ rrp/PanelGroup {:direction panes-direction :style {:height (:height config)}}
      ($ rrp/Panel
        {:default-size (:editor config)}
        ($ rrp/PanelGroup
          {:direction "horizontal"
           :units "pixels"}
          ($ rrp/Panel
            {:ref sidebar-pane-ref
             :max-size 260
             :default-size 260
             :collapsed-size 0
             :class "flex"}
            ($ sidebar/sidebar
              {:open? sidebar?
               :tree tree
               :set-tree set-tree
               :set-current-file set-current-file
               :current-file current-file}))
          ($ rrp/Panel
            ($ tab-bar
              {:set-current-file set-current-file
               :current-file current-file
               :embedded? embedded?})
            ($ editor {:on-mount set-editor
                       :on-change on-editor-change
                       :font-size (if embedded? 12 14)}))))
      (when preview?
        ($ resize-handler))
      ($ rrp/Panel
        {:ref preview-panel-ref
         :default-size (:preview config)
         :min-size 30
         :collapsed-size 0
         :collapsible true}
        ($ rrp/PanelGroup {:direction "vertical"}
          (when (:browser-bar? config)
            ($ browser-tool-bar {:id id :on-reload #(set-reloading :reloading)}))
          ($ rrp/Panel
            {:class "flex relative"
             :default-size 70}
            ($ output)
            (when-not (and ready? reloaded?)
              ($ :.absolute.w-full.h-full.bg-zinc-800.text-zinc-200.flex.items-center.justify-center
                ($ icons/ArrowPathIcon {:class "w-16 h-16 animate-spin"}))))
          (when console-expanded?
            ($ resize-handler {:direction "vertical"}))
          ($ rrp/Panel
            {:ref console-pane-ref
             :class "flex"
             :min-size 0}
            ($ console/console
              {:on-toggle set-console-expanded
               :console? console-expanded?
               :reloaded? reloaded?})))))))

(when ^boolean goog/DEBUG
  (def cleanup-editor-models-hook nil)
  (defn ^:dev/after-load cleanup-editor-models []
    (cleanup-editor-models-hook)))

(defn use-monaco-models [^js editor-inst files current-file]
  (let [^js m @pmonaco/lib-monaco
        [models set-models] (uix/use-state {})
        models (hooks/use-memo-dep models)
        current-model (models (get-in files [current-file :id]))
        files-id+path (hooks/use-memo-dep
                        (into #{} (keep #(when (and (= "file" (:type %)) (not (str/ends-with? (:path %) "/*")))
                                           (select-keys % [:id :path])))
                                  (vals files)))
        model-ids (hooks/use-memo-dep (into #{} (keys models)))
        new-file-paths (hooks/use-memo-dep (->> files-id+path (remove (comp model-ids :id)) (map :path)))
        deleted-model-ids (hooks/use-memo-dep (into #{}
                                                    (remove #(some (comp #{%} :id) files-id+path))
                                                    model-ids))
        create-models (hooks/use-event
                        (fn [new-file-paths]
                          (->> new-file-paths
                               (map files)
                               (mapv (fn [{:keys [id path content]}]
                                       (let [result (pinfer/parenMode content)
                                             content (if (.-success result)
                                                       (.-text result)
                                                       content)]
                                         (bus/log :editor "create model" path)
                                         [id (.createModel (.-editor m) content "clojure" (.parse (.-Uri m) (str "file:///" path)))]))))))
        dispose-all! (hooks/use-event
                       (fn []
                         (run! #(.dispose %) (vals models))
                         (set-models {})))]

    (when ^boolean goog/DEBUG
      ;; dispose all models after hot-reload in DEV
      (set! cleanup-editor-models-hook dispose-all!))

    (uix/use-effect
      (fn []
        ;; dispose models for deleted files
        (when (seq deleted-model-ids)
          (->> deleted-model-ids
               (map models)
               (run! (fn [model]
                       (bus/log :editor "dispose model" (.. model -uri -path))
                       (.dispose model)))))

        ;; create models for new files
        (let [new-models (when (seq new-file-paths)
                           (create-models new-file-paths))]
          (set-models #(into (apply dissoc % deleted-model-ids) new-models))))
      [deleted-model-ids new-file-paths])

    ;; disposing all models when unmounting 
    (uix/use-effect (fn [] #(dispose-all!)) [])

    (uix/use-effect
      (fn []
        ;; set editor models associated with current-file
        (when (and editor-inst current-model)
          (bus/log :editor "set model" (.. current-model -uri -path))
          (.setModel editor-inst current-model)))
      [current-model editor-inst])))

(defn use-save-file-structure-changes [id files set-save-status embedded?]
  (let [navigate (rrd/useNavigate)
        mounted? (uix/use-ref false)
        save-project (hooks/use-event
                       #(let [token (js/localStorage.getItem (str "uixp/" id))]
                          (if token
                            (do (bus/log :editor "saving project structure")
                                (api/save-project token id {:files files} set-save-status))
                            (do (bus/log :editor "structure changed: saving (forking) someone's project")
                                (api/create-project navigate files set-save-status)))))
        paths (hooks/use-memo-dep (->> (keys files) (remove #(str/ends-with? % "/*"))))]
    (uix/use-effect
      (fn []
        (when-not embedded?
          (when @mounted? (save-project))
          (reset! mounted? true)))
      [paths embedded?])))

(m/defmemo status-bar []
  ($ :.bg-zinc-800.w-screen.px-2.py-1.text-zinc-400.text-xs.relative
    {:style {:z-index 2}}
    (m/get-version)))

(defn use-save-status [initial set-files]
  (let [[save-status set-save-status] (uix/use-state initial)
        set-save-status (hooks/use-event
                          (fn [status & [file]]
                            (bus/log :editor "save-status" status file)
                            (set-save-status status)
                            (cond
                              (and (= :pending status) file)
                              (set-files #(assoc-in % [file :save-status] status))

                              (and (= :idle status) (not file))
                              (set-files (fn [files]
                                           (coll/map-vals #(assoc % :save-status status) files))))))]
    [save-status set-save-status]))

(defui project* [{:keys [embedded?]}]
  (let [[editor-inst set-editor] (uix/use-state nil)
        [sidebar? set-sidebar] (uix/use-state (not embedded?))
        {:keys [id]} (bean/bean (rrd/useParams))
        [preview? set-preview] (uix/use-state true)
        project (rrd/useLoaderData)
        [tree set-tree files] (file-tree/use-file-tree (:files project))
        [save-status set-save-status] (use-save-status :idle set-tree)
        [current-file set-current-file] (uix/use-state cfg/DEFAULT_FILE)
        eval-cljs (hooks/use-event #(when preview? (eval-files files)))
        [ready? reloaded? set-reloading] (use-init-cljs-env editor-inst eval-cljs)
        [save-status on-editor-change save-project save-project*] (use-save-handler id editor-inst project files set-tree current-file save-status set-save-status embedded?)]

    (use-monaco-models editor-inst files current-file)
    (use-save-file-structure-changes id files set-save-status embedded?)

    ;; always eval from the entry point
    (let [files (hooks/use-memo-dep (select-eval-files files))]
      (uix/use-effect
        (fn []
          (when ready?
            (bus/log :cljs-env "eval")
            (eval-cljs)))
        [ready? files]))

    ($ ctx/editor-provider
      {:value {:files files
               :set-current-file set-current-file
               :project project
               :save-project save-project*
               :set-sidebar set-sidebar}}
      (when-not embedded?
        ($ top-bar/top-bar
          {:id id
           :save-status save-status
           :save-project save-project
           :editor-inst editor-inst
           :set-sidebar set-sidebar
           :set-preview set-preview
           :eval-cljs eval-cljs}))
      ($ editor-layout
        {:id id
         :set-editor set-editor
         :on-editor-change on-editor-change
         :preview? preview?
         :set-reloading set-reloading
         :ready? ready?
         :reloaded? reloaded?
         :set-current-file set-current-file
         :current-file current-file
         :sidebar? sidebar?
         :tree tree
         :set-tree set-tree
         :embedded? embedded?})
      (when-not embedded?
        ($ status-bar)))))

(defui whats-new-dialog []
  ($ ui/dialog
    {:title "What's new?"
     :default-open? true}
    ($ :.text-md {:dangerouslySetInnerHTML #js {:__html (m/md->html "whats-new/29_08_2023.md")}})))

(defui project [{:keys [embedded?]}]
  (let [[monaco-ready? set-monaco-ready!] (uix/use-state false)
        [qm? set-qm!] (uix/use-state false)]
    (uix/use-effect
      (fn []
        (-> @pmonaco/setup-editor
            (.then #(set-monaco-ready! true))))
      [])
    #_(hooks/use-event-listener
        js/window "keydown"
        (uix/use-callback
          #(when (and (= "p" (.-key %)) (.-metaKey %))
             (.preventDefault %)
             (set-qm! true))
          []))
    ($ :<>
      ($ :.bg-zinc-900.w-screen.h-screen
        #_($ whats-new-dialog)
        (when monaco-ready?
          ($ tooltip/Provider
            {:delay-duration 300}
            ($ project* {:embedded? embedded?}))))
      ;; TODO: command palette
      #_
      ($ ui/dialog
        {:open? qm?
         :on-open-change set-qm!
         :overlay? false}
        ($ :.text-md {:dangerouslySetInnerHTML #js {:__html (m/md->html "whats-new/29_08_2023.md")}})))))

(defonce __init_log_toggle
  (do
    (.addEventListener js/window "keydown"
                       (fn [e]
                         (when (and
                                 (.-ctrlKey e)
                                 (.-shiftKey e)
                                 (.-metaKey e)
                                 (= "T" (.-key e)))
                           (set! js/window.__UIX_DEBUG_LOG__ (not js/window.__UIX_DEBUG_LOG__))
                           (if js/window.__UIX_DEBUG_LOG__
                             (js/console.log "debug logging enabled")
                             (js/console.log "debug logging disabled")))))
    nil))
