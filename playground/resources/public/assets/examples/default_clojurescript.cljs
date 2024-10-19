(ns playground.default-clojurescript
  (:require [clojure.string :as str]))

(defn fetch-joke []
  (-> (js/fetch "https://api.chucknorris.io/jokes/random")
      (.then #(.json %))
      (.then #(js->clj % :keywordize-keys true))))

(-> (js/Promise.all (map #(fetch-joke) (range 10)))
    (.then (fn [jokes]
             (->> jokes
                  (sort-by (comp #(.getTime (js/Date. %)) :created_at))
                  (map :value)
                  (str/join "\n\n")
                  (set! (.-innerText (js/document.getElementById "root")))))))
