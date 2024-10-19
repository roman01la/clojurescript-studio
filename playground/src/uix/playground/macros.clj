(ns uix.playground.macros
  (:require [cljs.analyzer :as ana]
            [cljs.env :as env]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [markdown.core :as md]
            [shadow.cljs.util :as util])
  (:import (java.nio.file Paths)))

(defn- get-file-path [^String path]
  (-> (.getPath (io/resource "public/index.html"))
      (Paths/get (into-array String []))
      .getParent
      .getParent
      .getParent
      (.resolve path)
      .normalize
      .toAbsolutePath
      .toString))

(defn- slurp-resource [env path]
  (let [current-ns (-> env :ns :name)
        path (get-file-path path)
        rc (io/file path)]

    (when-not (.exists rc)
      (throw (ana/error env (str "Resource not found: " path))))

    (when env/*compiler*
      (let [last-mod (util/url-last-modified (.toURL rc))]
        (swap! env/*compiler* assoc-in [::ana/namespaces current-ns ::resource-refs path] last-mod)))

    (slurp rc)))

(defn get-version* []
  (:out (apply sh/sh (str/split "git rev-parse --short HEAD" #" "))))

(defmacro get-version []
  (get-version*))

(defmacro read-deps []
  (let [deps (edn/read-string (slurp-resource &env "./deps.edn"))
        clojure-deps (->> (into (-> deps :aliases :bundle :extra-deps)
                                (-> deps :aliases :dev :extra-deps))
                          (mapv (fn [[k v]]
                                  [(str k) (:mvn/version v)])))
        npm-deps (-> (slurp-resource &env "./package.json")
                     json/read-str
                     (get "dependencies")
                     vec)]
    [clojure-deps npm-deps]))

(defmacro md->html [path]
  (md/md-to-html-string (slurp-resource &env (.toString (.getPath (io/resource path))))))

(defmacro defmemo [name args & body]
  `(def ~name
     (uix.core/memo
       (uix.core/fn ~args ~@body))))
