(ns uix.playground.file-tree-editor
  (:require [clojure.string :as str]
            [clojure.spec.alpha :as s]))

(s/def :ui/state #{:name/not-set :name/rename})

(defn default-tmp-file-node [path]
  {:type "file"
   :name nil
   :ui/state :name/not-set
   :path (str path "/*")})

(defn add-tmp-file [files parent-node]
  (let [node (default-tmp-file-node (:path parent-node))]
    (-> files
        (assoc (:path node) node)
        ;; open parent dir
        (assoc-in [(:path parent-node) :state/opened?] true))))

(defn default-tmp-folder-node [path]
  {:type "folder"
   :name nil
   :ui/state :name/not-set
   :state/opened? false
   :path (str path "/*")})

(defn add-tmp-folder [files parent-node]
  (let [node (default-tmp-folder-node (:path parent-node))]
    (-> files
        (assoc (:path node) node)
        ;; open parent dir
        (assoc-in [(:path parent-node) :state/opened?] true))))

(defn set-file-renaming [files node]
  (assoc-in files [(:path node) :ui/state] :name/rename))

(defn rename-file [files node new-name]
  ;; 1. remove existing entry
  ;; 2. add a new entry with a new path and name
  (let [new-name (if (str/ends-with? new-name ".cljs") new-name (str new-name ".cljs"))
        path (str/join "/" (-> (:path node) (str/split #"/") butlast (concat [new-name])))
        new-file (-> (files (:path node))
                     (assoc :path path :name new-name)
                     (dissoc :ui/state))]
    (-> files
        (dissoc (:path node))
        (assoc path new-file))))

(defn rename-folder [files node new-name]
  ;; rewrite path for descendants of the current entry
  (let [path (str/join "/" (-> (:path node) (str/split #"/") butlast (concat [new-name])))]
    (->> files
         (reduce-kv
           (fn [ret fpath file]
             (if (str/starts-with? fpath (:path node))
               (let [path (str/replace fpath (:path node) path)
                     file (-> file
                              (dissoc :ui/state)
                              (assoc :path path :name (last (str/split path #"/"))))]
                 (-> ret
                     (dissoc fpath)
                     (assoc path file)))
               (assoc ret fpath file)))
           {}))))

(defn- slice [s from to]
  (subs s from (+ (count s) to)))

(defn new-file-name+files [files node new-name]
  (let [path (str/replace (munge new-name) #"\." "/")
        segs (-> (:path node) (slice 0 -1) (str path ".cljs") (str/split #"/"))
        filename (last segs)
        paths (->> (iterate butlast segs)
                   (take (count segs))
                   (map #(str/join "/" %)))
        files (remove files paths)]
    [filename files]))

(defn add-namespace [files node new-name & [content]]
  ;; 1. remove existing entry
  ;; 2. add a file
  ;; 3. add/rewrite dirs structure
  (let [[filename [file & dirs]] (new-file-name+files files node new-name)
        content (or content
                    (let [pre (->> (-> (:path node) (slice 0 -1) (str/split #"/"))
                                   (drop 2))
                          ns-name (if (seq pre)
                                    (str (str/join "." pre) "." new-name)
                                    new-name)]
                      (str "(ns " ns-name ")")))]

    (-> files
        (dissoc (:path node))
        (assoc file {:type "file"
                     :name filename
                     :path file
                     :content content
                     :id (str (random-uuid))
                     :created_at (js/Date.now)})
        (into (map (fn [path] [path {:type "folder"
                                     :name (last (str/split path #"/"))
                                     :path path
                                     :state/opened? true}])
                   dirs)))))

(defn new-folder-path [node new-name]
  (-> (:path node) (slice 0 -1) (str new-name)))

(defn add-folder [files node new-name]
  ;; 1. remove tmp entry
  ;; 2. add a new entry with updated path/name
  (let [path (new-folder-path node new-name)
        file (-> node
                 (assoc :name new-name :path path)
                 (dissoc :ui/state))]
    (-> files
        (dissoc (:path node))
        (assoc path file))))

(defn delete-file [files node]
  (->> files
       (remove (comp #(str/starts-with? % (:path node)) key))
       (into {})))
