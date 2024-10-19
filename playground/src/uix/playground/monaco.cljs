(ns uix.playground.monaco
  (:require ["@monaco-editor/react" :as monaco]
            [clojure.string :as str]
            [uix.playground.api :as api]
            [uix.playground.bus :as bus]))

(defonce lib-monaco (atom nil))
(def monaco-editor (atom nil))

(defn- auto-completion [kws ^js model ^js position]
  (let [last-chars (.getValueInRange model #js {:startLineNumber (.-lineNumber position)
                                                :startColumn 0
                                                :endLineNumber (.-lineNumber position)
                                                :endColumn (.-column position)})]
    (-> (bus/dispatch+ [:editor/auto-completion last-chars])
        (.then (fn [^js r]
                 (set! (.-suggestions r)
                       (.concat (.-suggestions r) kws))
                 r)))))

(defn- provide-definition [^js model ^js position]
  (let [models (.getModels (.-editor ^js @lib-monaco))
        files (->> models
                   (filter #(str/starts-with? (.. % -uri -path) "/root/src"))
                   (map (fn [model]
                          [(.. model -uri -path) (.getValue model)]))
                   (into {}))]
    (bus/dispatch+ [:editor/go-to-definition files (.. model -uri -path) (.-lineNumber position) (.-column position)])))

(defonce setup-editor
  (delay
    (bus/log :editor "setting up the editor")
    ;; load Clojure language support
    (.config monaco/loader #js {:paths #js {"vs/basic-languages/clojure/clojure" "/assets/clojure"}})
    (-> (js/Promise.all [(.init monaco/loader)
                         (api/fetch-json "/assets/tailwind_keywords.json")
                         (api/fetch-json "/assets/events_keywords.json")
                         (api/fetch-json "/assets/html_keywords.json")])
        (.then (fn [[^js m tailwind-keywords events-keywords html-keywords]]
                 (let [kws (into-array
                             (for [k (.concat tailwind-keywords events-keywords html-keywords)
                                   :let [k (str k)]]
                               #js {:label k
                                    :insertText k
                                    :kind 17}))]

                   ;; store monaco lib ref
                   (reset! lib-monaco m)

                   ;; setup autocompletion
                   (.registerCompletionItemProvider (.-languages m) "clojure"
                                                    #js {:provideCompletionItems (partial auto-completion kws)
                                                         :triggerCharacters #js ["/" "(" "." ":"]})

                   ;; setup go to definition
                   #_
                   (.registerDefinitionProvider (.-languages m) "clojure"
                                                #js {:provideDefinition #(provide-definition %1 %2)})

                   ;; load color theme
                   (-> (js/fetch "/assets/theme.json")
                       (.then #(.json %))
                       (.then #(.defineTheme (.-editor m) "oceanic-next" %)))))))))
