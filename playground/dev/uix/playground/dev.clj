(ns uix.playground.dev
  (:require [shadow.http.push-state :as push-state]))

(defn handler* [req]
  (-> (push-state/handle req)
      #_(update :headers assoc
                "Cross-Origin-Embedder-Policy" "require-corp"
                "Cross-Origin-Opener-Policy" "same-origin")))

(def handler #'handler*)
