(ns uix.playground.config
  (:require [uix.playground.macros :as macros]))

(def local-deps
  (macros/read-deps))

(def APP_HOST "http://localhost:3000/")

(def API_HOST "http://localhost:8788/")

(def DEFAULT_FILE
  "root/src/playground/core.cljs")

(def DEFAULT_NS
  'playground.core)
