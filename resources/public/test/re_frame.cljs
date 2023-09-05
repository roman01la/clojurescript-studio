(ns test.re-frame
  (:require [reagent.core :as r]
            [reagent.dom :as dom]
            [re-frame.core :as rf]
            [clojure.string :as str]))

(rf/reg-event-db :app/init
  (fn [_ [_ db]]
    db))

(rf/reg-fx :fetch
  (fn [{:keys [url on-done on-error]}]
    (-> (js/fetch url)
        (.then #(.json %))
        (.then #(rf/dispatch [on-done (js->clj % :keywordize-keys true)]))
        (.catch #(rf/dispatch [on-error %])))))

(rf/reg-event-db :search/find-repos-done
  (fn [{:keys [db]} [_ resp]]
    (update db :search merge {:results resp :status :idle})))

(rf/reg-event-fx :search/find-repos
  (fn [{:keys [db]} [_ query]]
    {:fetch {:url (str "https://api.github.com/orgs/" query "/repos")
             :on-done :search/find-repos-done}
     :db (assoc-in db [:search :status] :in-progress)}))

(rf/reg-sub :app/search
  (fn [db _]
    (:search db)))

(rf/reg-sub :search/repos
  :<- [:app/search]
  (fn [search _]
    (:results search)))

(rf/reg-sub :search/status
  :<- [:app/search]
  (fn [search _]
    (:status search)))

(defn repositories []
  [:div
   (for [repo @(rf/subscribe [:search/repos])]
     [:div {:key (:id repo)}
      [:div (:name repo)]
      [:div (:description repo)]])])

(defn button []
  (r/with-let [query (r/atom "")
               handle-submit (fn [e]
                               (.preventDefault e)
                               (when-not (str/blank? @query)
                                 (rf/dispatch [:search/find-repos @query])))]
    [:div
     [:form {:on-submit handle-submit}
      [:input
       {:value @query
        :placeholder "GitHub org name"
        :on-change #(reset! query (.. % -target -value))}]
      [:button "Search"]]
     (if (= :in-progress @(rf/subscribe [:search/status]))
       [:div "Loading..."]
       [repositories])]))

(rf/dispatch [:app/init {:search {:results [] :status :idle}}])
(dom/render [button] (js/document.getElementById "root-re-frame"))
