(ns uix.playground.utils
  (:require [clojure.string :as str]))

(defn munge-ns [ns-name]
  (str/replace-all (str (munge ns-name)) #"\." "/"))

(defn ns->path [ns-name]
  (str "src/" (munge-ns ns-name) ".cljs"))
