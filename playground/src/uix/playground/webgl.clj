(ns uix.playground.webgl
  (:require [cljs.analyzer :as ana]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [shadow.build.resource :as rc]
            [shadow.cljs.util :as util]
            [shadow.resource :as resource])
  (:import (java.nio.file Paths)))

(defn to-absolute-path [env path]
  (let [current-ns (-> env :ns :name)

        path
        (cond
          (str/starts-with? path "/")
          (subs path 1)

          (str/starts-with? path ".")
          (let [resource-name
                (util/ns->cljs-filename current-ns)

                parent
                (-> (Paths/get resource-name (into-array String []))
                    (.getParent))]

            (when-not parent
              (throw (ana/error env (str "Could not resolve " path " from " current-ns))))

            (-> parent
                (.resolve path)
                (.normalize)
                (.toString)
                (rc/normalize-name)))

          :else
          path)

        rc (io/resource path)]
    rc))

(defn include-require [env source line path]
  (let [path (to-absolute-path env (str path ".glsl"))
        include-source (slurp path)]
    (str/replace source line include-source)))

(defn pre-process [env source]
  (binding [*out* *err*]
    (let [results (re-seq #"#pragma include: '(.+)'" source)]
      (reduce #(apply include-require env %1 %2) source results))))

(defmacro read-shader [path]
  (pre-process &env (resource/slurp-resource &env path)))
