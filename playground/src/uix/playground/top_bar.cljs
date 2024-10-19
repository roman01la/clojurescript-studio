(ns uix.playground.top-bar
  (:require [react-router-dom :as rrd]
            [jszip :as JSZip]
            ["@heroicons/react/20/solid" :as icons]
            [clojure.string :as str]
            [uix.core :as uix :refer [defui $]]
            [uix.playground.api :as api]
            [uix.playground.macros :as m]
            [uix.playground.bus :as bus]
            [uix.playground.config :as cfg]
            [uix.playground.context :as ctx]
            [uix.playground.ui :as ui]
            [uix.playground.utils :as utils]))

(defn- get-deps+ []
  (let [[clojure-deps npm-deps] cfg/local-deps]
    (-> (js/Promise.all [(bus/dispatch+ [:runtime/get-playground-ns-requires])
                         (bus/dispatch+ [:runtime/get-playground-ns])])
        (.then
          (fn [[requires entry-ns]]
            [(->> clojure-deps
                  (filter (fn [dep] (some #(str/includes? (first dep) %) requires)))
                  (mapv (fn [[k v]]
                          [(symbol k) v])))
             (filterv (fn [dep] (some #(str/includes? (first dep) %) requires)) npm-deps)
             entry-ns])))))

(defn- download-project+* [id code [clj-deps npm-deps entry-ns]]
  (let [zip (JSZip.)
        code-path (utils/ns->path entry-ns)
        files {code-path code
               "resources/public/index.html" "<link rel=\"stylesheet\" href=\"/main.css\">\n<div id=root></div>\n<script src=/out/main.js></script>"
               "resources/main.css" "@tailwind base;\n@tailwind components;\n@tailwind utilities;\n"
               "README.md" "```\nyarn install\nyarn dev\n```"
               "package.json" (js/JSON.stringify
                                #js {"devDependencies"
                                     (-> (into {} npm-deps)
                                         (assoc "react" "^18.2.0"
                                                "react-dom" "^18.2.0"
                                                "shadow-cljs" "2.25.3"
                                                "tailwindcss" "^3.3.3"
                                                "concurrently" "^8.2.1")
                                         clj->js)
                                     "scripts"
                                     #js {"dev:css" "tailwindcss -i ./resources/main.css -o ./resources/public/main.css -w"
                                          "dev:cljs" "shadow-cljs watch main"
                                          "release:css" "tailwindcss -i ./resources/main.css -o ./resources/public/main.css -m"
                                          "release:cljs" "shadow-cljs release main"
                                          "dev" "concurrently \"yarn dev:css\" \"yarn dev:cljs\""
                                          "release" "concurrently \"yarn release:css\" \"yarn release:cljs\""}}
                                nil
                                " ")
               "tailwind.config.js" "/** @type {import('tailwindcss').Config} */\nmodule.exports = {\n  content: [\"./src/**/*.cljs\"],\n  theme: {\n    extend: {},\n  },\n  plugins: [],\n}"
               "shadow-cljs.edn" (with-out-str
                                   (cljs.pprint/pprint
                                     {:dev-http {3000 "./resources/public/"}
                                      :source-paths ["src"]
                                      :dependencies clj-deps
                                      :builds {:main {:target :browser
                                                      :output-dir "resources/public/out"
                                                      :asset-path "/out"
                                                      :modules {:main {:entries [entry-ns]}}}}}))}]
    (doseq [[name contents] files]
      (.file zip name contents))

    (-> (.generateAsync zip #js {:type "blob"})
        (.then js/URL.createObjectURL)
        (.then (fn [url]
                 (let [link (js/document.createElement "a")]
                   (set! (.-href link) url)
                   (set! (.-download link) (str id ".zip"))
                   (.click link)
                   (js/URL.revokeObjectURL url)))))))

(defn- download-project+ [id code]
  (-> (get-deps+) (.then #(download-project+* id code %))))

(defn create-embed-html [url]
  (str "<iframe src=\"" url "\" style=\"width:100%;height:500px;border:0;border-radius:4px;overflow:hidden;\" allow=\"accelerometer; ambient-light-sensor; camera; encrypted-media; geolocation; gyroscope; hid; microphone; midi; payment; usb; vr; xr-spatial-tracking\" sandbox=\"allow-forms allow-modals allow-popups allow-presentation allow-same-origin allow-scripts\"></iframe>"))

(defn copy-link [url msg]
  (-> (js/navigator.clipboard.writeText url)
      (.then #(js/alert msg))
      (.catch #(js/alert "Something went wrong, please copy it manually"))))

(defui button-link [props]
  ($ :a.flex.items-center.gap-2.bg-zinc-700.hover:bg-zinc-600.px-2.py-1.h-full.rounded-md.border.border-zinc-600.hover:border-zinc-500.text-xs.text-zinc-200.shadow-md
    (assoc-in props [:style :line-height] 0)))

(defui button [props]
  ($ :button.flex.items-center.gap-2.bg-zinc-700.hover:bg-zinc-600.px-2.py-1.h-full.rounded-md.border.border-zinc-600.hover:border-zinc-500.text-xs.text-zinc-200.shadow-md
    (assoc-in props [:style :line-height] 0)))

(def button-fwd
  (uix/forward-ref
    (fn [{:keys [on-click onClick] :as props}]
      ($ button (-> props
                    (dissoc :on-click :onClick)
                    (assoc :on-click (fn [e]
                                       (on-click e)
                                       (onClick e))))))))

(def button-link-fwd
  (uix/forward-ref #($ button-link %)))

(def icon-play
  ($ :svg.w-ful.h-full {:view-box "0 0 24 24"}
    ($ :path
      {:fill "#6CCF1D"
       :stroke "#53A215"
       :d "M20.5 12C20.5 12.0477 20.4815 12.1478 20.3988 12.3116C20.3185 12.471 20.1922 12.6618 20.0161 12.8823C19.6637 13.3233 19.1461 13.8405 18.5022 14.4058C17.2168 15.5341 15.4793 16.8098 13.6929 17.9914C11.9074 19.1724 10.0882 20.2496 8.64452 20.9835C7.9207 21.3514 7.30456 21.6262 6.84067 21.7859C6.60729 21.8662 6.42973 21.9113 6.30564 21.9286C6.29217 21.9304 6.28007 21.9319 6.26926 21.933C6.26053 21.9142 6.2508 21.8918 6.24024 21.8653C6.18349 21.7226 6.12256 21.511 6.06119 21.2275C5.93915 20.6638 5.82887 19.8801 5.73499 18.9385C5.54761 17.0592 5.43033 14.6012 5.40403 12.1267C5.37772 9.65153 5.44261 7.17428 5.61659 5.255C5.70376 4.29331 5.81703 3.487 5.95537 2.89614C6.02476 2.59979 6.09681 2.37375 6.16741 2.21522C6.22216 2.09226 6.2618 2.04268 6.27338 2.0287C6.28928 2.02437 6.33465 2.01459 6.42621 2.01787C6.56152 2.02271 6.74855 2.05432 6.99018 2.12183C7.47214 2.25649 8.09867 2.51298 8.82917 2.87124C10.2857 3.58555 12.0871 4.67034 13.845 5.87233C15.6028 7.07425 17.3002 8.38184 18.5525 9.53656C19.1798 10.115 19.6835 10.6442 20.0263 11.0942C20.1977 11.3191 20.321 11.5141 20.3997 11.677C20.4807 11.8444 20.5 11.9484 20.5 12Z"})))

(m/defmemo editable-project-name [{:keys [value on-submit id]}]
  (let [[rename-value set-rename-value] (uix/use-state nil)
        on-change (fn []
                    (when (and (not= rename-value value)
                               (not (str/blank? rename-value)))
                      (on-submit {:name rename-value}))
                    (set-rename-value nil))]
    (cond
      (not (js/localStorage.getItem (str "uixp/" id)))
      ($ :.text-zinc-300.font-normal.text-xs.hidden.lg:block
        value)

      rename-value
      ($ :input.bg-zinc-600.rounded-sm.p-1.text-zinc-200.text-xs.outline-0.w-48
        {:auto-focus true
         :value rename-value
         :on-change #(set-rename-value (.. % -target -value))
         :on-focus #(.select (.-target %))
         :on-blur on-change
         :on-key-down (fn [^js e]
                        (case (.-key e)
                          "Enter" (on-change)
                          "Escape" (set-rename-value nil)
                          nil))})

      :else
      ($ ui/tooltip {:label "Rename project"}
        ($ :button.text-zinc-300.font-normal.text-xs.hidden.lg:block
          {:on-click #(set-rename-value value)}
          value)))))

(defui toggle-btn [{:keys [set-sidebar class]}]
  ($ ui/tooltip {:label "Toggle sidebar"}
    ($ :button.group {:on-click #(set-sidebar not)}
      ($ icons/Bars3BottomLeftIcon {:class ["w-4 h-4 text-zinc-300 transition-transform group-hover:translate-x-1" class]}))))

(m/defmemo top-bar [{:keys [id save-status save-project editor-inst set-sidebar set-preview eval-cljs]}]
  (let [navigate (rrd/useNavigate)
        {:keys [project files]
         save-project* :save-project} (uix/use-context ctx/editor)
        fork-project #(api/create-project navigate files)]
    ($ :.h-10.px-4.bg-zinc-800.flex.gap-3.items-center.justify-between.border-b.border-b-zinc-700
      ($ toggle-btn {:set-sidebar set-sidebar})
      ($ :.flex.gap-3.items-center
         ($ ui/tooltip {:label "Publicly available"}
            ($ icons/GlobeEuropeAfricaIcon {:class "w-4 h-4 text-zinc-300 hidden lg:block"}))
         ($ editable-project-name
           {:value (or (:name project) id)
            :id id
            :on-submit save-project*})
         (case save-status
           :pending
           ($ ui/tooltip {:label "Pending changes"}
              ($ icons/PencilIcon {:class "w-4 h-4 text-zinc-300"}))
           :progress
           ($ ui/tooltip {:label "Saving..."}
              ($ icons/ClockIcon {:class "w-4 h-4 text-yellow-400"}))
           :done
           ($ ui/tooltip {:label "Saved successfully"}
              ($ icons/CheckCircleIcon {:class "w-4 h-4 text-lime-400"}))
           :error
           ($ ui/tooltip {:label "Couldn't save changes"}
              ($ icons/ExclamationTriangleIcon {:class "w-4 h-4 text-red-400"}))

           ($ :.w-4.h-4)))
      ($ :.flex.gap-3.h-full.py-2.overflow-x-auto
         ($ ui/tooltip {:label "Run code"}
            ($ button-fwd
               {:on-click #(eval-cljs (.getValue editor-inst))}
               icon-play))
         ($ ui/tooltip {:label "Toggle preview"}
            ($ button-fwd
               {:on-click #(set-preview not)}
               ($ icons/WindowIcon {:class "w-3 h-3"})))
         ($ ui/tooltip {:label "Save project"}
            ($ button-fwd
               {:on-click save-project}
               ($ icons/InboxArrowDownIcon {:class "w-3 h-3"})
               "Save"))
         ($ ui/tooltip {:label "Copy share link"}
            ($ button-fwd
               {:on-click #(copy-link js/window.location.href "Share link copied!")}
               ($ icons/LinkIcon {:class "w-3 h-3"})
               "Share"))
         ($ ui/tooltip {:label "Copy HTML embed"}
            ($ button-fwd
               {:on-click #(copy-link (create-embed-html (str cfg/APP_HOST "e/" id)) "Embed copied!")}
               ($ icons/ChevronUpDownIcon {:class "w-4 h-4 rotate-90"})
               "Embed"))
         ($ ui/tooltip {:label "Remix this project"}
            ($ button-fwd
               {:on-click fork-project}
               ($ icons/ArrowPathRoundedSquareIcon {:class "w-3 h-3"})
               "Remix"))
         ($ ui/tooltip {:label "As shadow-cljs project"}
            ($ button-fwd
               {:on-click #(download-project+ id (.getValue editor-inst))}
               ($ icons/ArrowDownOnSquareIcon {:class "w-3 h-3"})
               "Download"))
         ($ ui/tooltip {:label "Create a new project"}
            ($ button-fwd
               {:on-click #(navigate "/")}
               ($ icons/DocumentPlusIcon {:class "w-3 h-3"})
               "Create"))
         ($ ui/tooltip {:label "Report an issue"}
            ($ button-link-fwd
               {:href "https://github.com/roman01la/clojurescript-studio/issues"
                :target "blank"}
               ($ icons/BugAntIcon {:class "w-3 h-3"})))
         ($ ui/tooltip {:label "Support"}
            ($ button-link-fwd
               {:href "https://github.com/sponsors/roman01la"
                :target "blank"
                :class "group"}
               ($ icons/GiftIcon {:class "w-3 h-3 text-amber-300 group-hover:animate-bounce"})))))))
