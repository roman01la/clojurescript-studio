(ns uix.source
  (:require [cljs.analyzer :as ana]))

(defn source [env sym]
  #_(-> (ana/resolve-var env sym) :meta))
