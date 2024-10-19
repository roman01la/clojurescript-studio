(ns uix.playground.api
  (:require [clojure.string :as str]
            [random-word-slugs :as random-slug]
            [uix.playground.bus :as bus]
            [uix.playground.config :as cfg]))

(defn fetch-json [url]
  (-> (js/fetch url)
      (.then #(.json %))))

(defn- gen-id []
  (let [readable (random-slug/generateSlug)]
    (->> (random-uuid)
         str
         (re-matches #"^([a-z0-9]+)-.*")
         second
         (str readable "-"))))

(defn fetch [id method body]
  (js/fetch (str cfg/API_HOST "p/" id)
            #js {:method method
                 :headers #js {"Content-Type" "application/json"}
                 :body (when body (js/JSON.stringify body))}))

(defn get-projects-count []
  (-> (js/fetch (str cfg/API_HOST "count"))
      (.then #(.json %))
      (.then #(.-count %))))

(defn load-project [id]
  (bus/log :api "load project" id)
  (-> (fetch id "GET" nil)
      (.then (fn [^js r]
               (if (not= 200 (.-status r))
                 (js/Promise.reject (.-status r))
                 r)))
      (.then #(.json %))
      (.then (fn [data]
               (if (.-files data)
                 (let [ret (js->clj data :keywordize-keys true)
                       files (->> (js/Object.entries (.-files data))
                                  (reduce (fn [ret [k v]]
                                            (assoc ret k (js->clj v :keywordize-keys true)))
                                          {}))]
                   (assoc ret :files
                     (-> files
                         (assoc-in ["root" :state/opened?] true)
                         (assoc-in ["root/src" :state/opened?] true)
                         (assoc-in ["root/src/playground" :state/opened?] true))))
                 (js->clj data :keywordize-keys true))))))

(defn load-owned-projects [limit]
  (bus/log :api "load owned projects")
  (let [ids (->> (js/Object.keys js/localStorage)
                 (filter #(str/starts-with? % "uixp/"))
                 (map #(.replace % "uixp/" "")))]
    (-> (js/Promise.all (map load-project ids))
        (.then (fn [projects]
                 (->> projects
                      (sort #(- (or (:updated_at %2) (:created_at %2))
                                (or (:updated_at %1) (:created_at %1))))
                      (map #(assoc %2 :id %1) ids)
                      (take limit)))))))

(defn create-project*
  ([id token files]
   (create-project* id token files 3))
  ([id token files retries]
   (-> (fetch id "POST" #js {:files (clj->js files) :token token})
       (.then (fn [r]
                (if (and (= 404 (.-status r)) (pos? retries))
                  (create-project* id token files (dec retries))
                  r))))))

(defn create-project
  ([navigate files]
   (create-project navigate files (fn [x])))
  ([navigate files on-change]
   #_(if (> (.-length code) 10e3)
       (js/alert "Can't save the project, character limit is 10,000"))
   (let [id (gen-id)
         token (str (random-uuid))]
     (bus/log :api "create project" id)
     (on-change :progress)
     (-> (create-project* id token files)
         (.then (fn [^js/Response r]
                  (if (= 200 (.-status r))
                    (do
                      (on-change :done)
                      (js/localStorage.setItem (str "uixp/" id) token)
                      (navigate (str "/p/" id)))
                    (do
                      (on-change :error)
                      (js/alert "Couldn't create a project. Please try again.")))))
         (.finally (fn []
                     (js/setTimeout #(on-change :idle) 1000)))))))

(defn delete-project [id]
  (when-let [token (js/localStorage.getItem (str "uixp/" id))]
    (bus/log :api "delete project" id)
    (-> (fetch id "DELETE" #js {:token token})
        (.then #(js/localStorage.removeItem (str "uixp/" id))))))

(defn- update-project [token id project]
  (bus/log :api "update project" id)
  (-> (fetch id "PUT" (clj->js (assoc project :token token)))
      (.then (fn [^js r]
               (when (not= 200 (.-status r))
                 (js/alert "Couldn't update the project. Please try again."))))))

(defn save-project
  ([id project on-change]
   (save-project nil id project on-change))
  ([token id project on-change]
   #_(if (> (.-length code) 10e3)
       (js/alert "Can't save the project, character limit is 10,000"))
   (let [token (or (js/localStorage.getItem (str "uixp/" id))
                   token)]
     (do
       (on-change :progress)
       (-> (update-project token id project)
           (.then #(on-change :done))
           (.catch #(on-change :error))
           (.finally (fn []
                       (js/setTimeout #(on-change :idle) 1000))))))))
