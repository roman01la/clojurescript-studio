(ns uix.playground.home-shared
  (:require [uix.core :refer [defui $] :as uix]
            #?@(:cljs [[react-router-dom :as rrd]
                       ["@heroicons/react/20/solid" :as icons]
                       ["timeago.js" :as time-ago]
                       [uix.playground.api :as api]
                       [uix.playground.ui :as ui]])))

(def project-types
  [{:name "ClojureScript"
    :description "Experiment with ClojureScript"
    :image "/assets/images/cljs_logo.png"
    :id "default-clojurescript"}
   {:name "React"
    :description "Create web apps with UIx, React and Tailwind CSS"
    :image "/assets/images/react_logo.png"
    :id "default-uix"}
   {:name "Three.js"
    :description "Create 3D scenes with UIx and React Three Fiber"
    :image "/assets/images/three_logo.png"
    :id "default-three-fiber"}
   {:name "Reagent"
    :description "Create web apps with Reagent and re-frame"
    :image "/assets/images/reagent_re_frame_logo.png"
    :id "default-reagent"}])

(defui link [{:keys [href class children]}]
  ($ #?(:cljs rrd/Link :clj :a)
    {#?@(:cljs [:to href]
         :clj  [:href href])
     :class class}
    children))

(defui project-type-card
  [{:keys [href]
    {:keys [name description image id]} :data}]
  ($ :.p-2.rounded-md.border.border-sky-800.flex.flex-col.justify-between.px-8.py-4.shadow-md.relative.overflow-hidden
    {:style {:background "linear-gradient(299deg, rgb(8 71 73 / 30%), rgb(14 56 83))"}}
    ($ :div
      {:style (merge
                {:position :absolute
                 :width 160
                 :height 160
                 :background-image (str "url(" image ")")
                 :background-repeat :no-repeat
                 :background-size "100%"
                 :opacity 0.5
                 :top -32
                 :right -64}
                (when (= "default-reagent" id)
                  {:width 200
                   :height 200
                   :top -62
                   :right -34
                   :opacity 0.8}))})
    ($ :.flex.flex-col.gap-2.relative
      ($ :.text-indigo-200
        name)
      ($ :.text-indigo-200.text-sm.font-light
        {:style {:text-shadow "1px 1px 0px rgba(0, 0, 0, 0.2)"}}
        description))
    ($ link
      {:href href
       :class "hover:text-lime-200 text-slate-100
               py-1 rounded-md border border-emerald-300 hover:border-emerald-200 shadow-md
               mt-4 relative text-center
               bg-gradient-to-bl from-emerald-400 to-lime-400
               hover:from-emerald-400 hover:to-lime-200"}
      "Create")))

(defui welcome-section []
  (let [[projects-count set-projects-count] (uix/use-state 0)]
    #?(:cljs
        (uix/use-effect
          #(-> (api/get-projects-count) (.then set-projects-count))
          []))
    ($ :<>
      ($ :.text-center.flex.flex-col.gap-4
        ($ :h1.text-5xl.bg-gradient-to-br.from-indigo-300.to-sky-100
          {:style {:background-clip :text
                   :-webkit-background-clip :text
                   :-webkit-text-fill-color :transparent
                   :line-height "64px"
                   :font-family "Alexandria"}}
          "ClojureScript Studio")
        ($ :h2.font-light.text-indigo-200 "Online coding sandbox tailored for web development")
        ($ :.font-light.text-indigo-300.text-sm (str (+ projects-count 95) " sandboxes created, and counting...")))
      ($ :.max-w-3xl.grid.gap-4.grid-rows-1.sm:grid-cols-1.md:grid-cols-2.lg:grid-cols-3
        (for [p project-types]
          ($ project-type-card
            {:key (:id p)
             :data p
             :href (str "/p/" (:id p))}))))))

(defui libraries-section []
  ($ :.text-center.max-w-2xl
    ($ :.text-xs.text-indigo-200.mb-2
      "Libraries included")
    (for [[lib link] [["uix" "https://github.com/pitch-io/uix"]
                      ["react" "https://react.dev/"]
                      ["tailwind" "https://tailwindcss.com/"]
                      ["react-query" "https://tanstack.com/query/v3/"]
                      ["react-router-dom" "https://reactrouter.com/en/main"]
                      ["@react-three/fiber" "https://docs.pmnd.rs/react-three-fiber/"]
                      #_["@react-three/drei" "https://github.com/pmndrs/drei"]
                      ["three" "https://threejs.org/"]
                      ["reagent" "https://github.com/reagent-project/reagent"]
                      ["re-frame" "https://github.com/day8/re-frame"]
                      ["react-charts" "https://react-charts.tanstack.com/"]
                      #_#_["promesa" "https://github.com/funcool/promesa"]
                          ["transit" "https://github.com/cognitect/transit-cljs"]]]
      ($ :a.text-xs.text-indigo-200.m-1.px-3.py-1.bg-blue-900.rounded-xl.border.border-blue-600.inline-block
        {:key lib
         :href link}
        lib))))

#?(:cljs
    (defui project-item [{:keys [created_at updated_at id name on-click-delete]}]
      ($ :div
        {:class "p-3 flex flex-col gap-4 text-xs text-indigo-200 rounded-md border border-sky-800 shadow-md"
         :style {:background "linear-gradient(329deg, rgba(8, 47, 73, 0.3), rgb(32 99 191 / 36%))"
                 :border-color "rgba(7, 89, 133, 0.7)"}}
        ($ rrd/Link
          {:to (str "/p/" id)
           :class "flex justify-between hover:underline"}
          ($ :span.mr-2
            {:style {:display :inline-block
                     :overflow :hidden
                     :text-overflow :ellipsis
                     :white-space :nowrap
                     :width "100%"}}
            (or name id))
          ($ icons/ArrowTopRightOnSquareIcon {:class "w-4 h-4"}))
        ($ :.flex.justify-between
          (time-ago/format (or updated_at created_at) "en_US")
          ($ :button.hover:text-amber-500
            {:on-click #(on-click-delete id)}
            ($ icons/XMarkIcon {:class "w-4 h-4"}))))))

#?(:cljs
    (defui recent-projects-section [{:keys [projects set-projects]}]
      (let [delete-project (uix/use-callback
                             #(some-> (api/delete-project %)
                                      (.then (partial api/load-owned-projects 20))
                                      (.then set-projects))
                             [set-projects])]
        (uix/use-effect
          #(-> (api/load-owned-projects 20) (.then set-projects))
          [set-projects])
        (when (seq projects)
          ($ :<>
             ($ :.text-md.text-indigo-200.text-center.mb-8.sm:mt-8.md:mt-0
                "My recent projects")
             ($ :.flex.justify-center.mb-32
                ($ :.grid.gap-4.max-w-2xl.grid-cols-2.sm:grid-cols-3.md:grid-cols-4.lg:grid-cols-4
                   (for [{:keys [id] :as p} projects]
                     ($ project-item (assoc p :key id :on-click-delete delete-project))))))))))

(defui footer []
  ($ :footer.text-slate-200.text-xs.py-4.text-center.flex.gap-4.justify-center
     ($ :span
        "Made by " ($ :a.text-emerald-400 {:href "https://www.romanliutikov.com/"} "Roman Liutikov"))
     ($ :a.text-emerald-400 {:href "https://github.com/roman01la/clojurescript-studio/"}
        ($ :img.w-4.h-4 {:src "/assets/images/github.svg"}))))

(defui home []
  (let [[projects set-projects] (uix/use-state [])]
    ($ :<>
      #?(:cljs
          ($ ui/canvas))
       ($ :header.py-2.w-screen.fixed.left-0.top-0
          ($ :.max-w-5xl.flex.gap-4.text-sm.text-indigo-300.justify-end
             {:style {:margin "0 auto"}}
             ($ :a.px-2.py-1.hover:text-indigo-400
                {:href "/p/default-clojurescript"}
                "Get started")
             ($ :a.px-2.py-1.hover:text-indigo-400
                {:href "/log"}
                "Dev log")))
       ($ :div.px-4.pt-2
          ($ :.min-h-screen.flex.flex-col.gap-8.justify-center.items-center.pt-4
             ($ welcome-section)
             ($ libraries-section)
             #?(:cljs
                 (when (seq projects)
                   ($ :.sm:hidden.md:flex.flex-col.items-center.text-indigo-200.cursor-pointer.transition-transform.hover:translate-y-1.sm:bottom-4
                      {:on-click #(js/window.scrollBy #js {:top (/ js/window.innerHeight 2) :behavior "smooth"})}
                      ($ icons/ChevronDownIcon {:class "w-8 h-8"})))))
          #?(:cljs
              ($ recent-projects-section
                 {:projects projects
                  :set-projects set-projects}))
          ($ footer)))))
