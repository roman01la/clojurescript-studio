(ns uix.playground.npm
  (:require [cljs.js :as cljs]
            [clojure.set :as set]
            [clojure.string :as str]
            [shadow.cljs.bootstrap.browser :as boot]
            [shadow.cljs.bootstrap.env :as boot.env]
            [uix.playground.api :as api]
            [uix.playground.config :as cfg])
  (:import [goog.net BulkLoader]))

(defn create-resource [ns path]
  (let [deps ['shadow.js]]
    {:ns ns,
     :resource-id [:shadow.build.npm/resource path],
     :type :shadow-js,
     :requires (set deps),
     :js-name path
     :provides #{ns},
     :timestamp (js/Date.now),
     :source-name path
     :deps deps}))

(defn- add-resource! [rc]
  (swap! boot.env/index-ref assoc-in [:sources (:resource-id rc)] rc)
  (swap! boot.env/index-ref update :sources-ordered conj rc)
  (swap! boot.env/index-ref (fn [idx]
                              (reduce
                                #(assoc-in %1 [:sym->id %2] (:resource-id rc))
                                idx
                                (:provides rc)))))

(defn add-source-package! [module version]
  (let [uri (str cfg/API_HOST "npm?name=" module "&v=" version)]
    (add-resource! (create-resource module uri))))

(defn add-source-file! [path uri]
  (add-resource! (create-resource path uri)))

(defn asset-path [& args]
  (if (str/starts-with? (first args) "http")
    (first args)
    (apply str (:path @boot/init-opts) args)))
#_
(defn load-namespaces
  "loads a set of namespaces, must be called after init"
  [compile-state-ref namespaces cb]
  {:pre [(set? namespaces)
         (every? symbol? namespaces)
         (fn? cb)]}
  (let [deps-to-load-for-ns
        (boot.env/find-deps namespaces)

        macro-deps
        (->> deps-to-load-for-ns
             (filter #(= :cljs (:type %)))
             (map :macro-requires)
             (reduce set/union)
             (map #(symbol (str % "$macros")))
             (into #{}))

        ;; second pass due to circular dependencies in macros
        deps-to-load-with-macros
        (boot.env/find-deps (set/union namespaces macro-deps))

        compile-state
        @compile-state-ref

        things-already-loaded
        (->> deps-to-load-with-macros
             (filter #(set/superset? @boot.env/loaded-ref (:provides %)))
             (map :provides)
             (reduce set/union))

        js-files-to-load
        (->> deps-to-load-with-macros
             (remove #(set/superset? @boot.env/loaded-ref (:provides %)))
             (map (fn [{:keys [ns provides js-name]}]
                    {:type :js
                     :ns ns
                     :provides provides
                     :uri (asset-path js-name)})))

        analyzer-data-to-load
        (->> deps-to-load-with-macros
             (filter #(= :cljs (:type %)))
             ;; :dump-core still populates the cljs.core analyzer data with an empty map
             (filter #(nil? (get-in compile-state [:cljs.analyzer/namespaces (:ns %) :name])))
             (map (fn [{:keys [ns ana-name]}]
                    {:type :analyzer
                     :ns ns
                     :uri (asset-path ana-name)})))

        load-info
        (-> []
            (into js-files-to-load)
            (into analyzer-data-to-load))]

    ;; this is transfered to cljs/*loaded* here to delay it as much as possible
    ;; the JS may already be loaded but the analyzer data may be missing
    ;; this way cljs.js is forced to ask first
    (swap! cljs/*loaded* set/union things-already-loaded)

    ;; may not need to load anything sometimes?
    (if (empty? load-info)
      (cb {:lang :js :source ""})

      (let [{load-info false npm-load-info true} (group-by #(str/starts-with? (:uri %) "http") load-info)
            uris (map :uri load-info)
            npm-uris (map :uri npm-load-info)

            loader
            (BulkLoader. (into-array uris))]

        (.listen loader js/goog.net.EventType.SUCCESS
                 (fn [e]
                   (let [texts (.getResponseTexts loader)]
                     (doseq [load (map #(assoc %1 :text %2) load-info texts)]
                       (boot/queue-task! #(boot/execute-load! compile-state-ref load)))
                     
                     ;; callback with dummy so cljs.js doesn't attempt to load deps all over again
                     (boot/queue-task! #(cb {:lang :js :source ""})))))

        (-> (js/Promise.all (map api/fetch-json npm-uris))
            (.then (fn [objs]
                     (->> objs
                          (mapcat #(js/Object.entries (.-deps ^js %)))
                          (run! #(apply add-source-file! %)))
                     (doseq [load (map #(assoc %1 :text (.-code ^js %2)) npm-load-info objs)]
                       (boot/queue-task! #(boot/execute-load! compile-state-ref load))))))


        (.load loader)))))
#_
(set! boot/load-namespaces load-namespaces)
