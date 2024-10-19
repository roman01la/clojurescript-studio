(ns uix.cf.worker
  (:require [itty-router :refer [Router]]
            [uix.playground.transit :as t]))

(defn with-cors [env resp]
  (doseq [[k v] {"Access-Control-Allow-Origin" (.-CORS_HOST env)
                 "Access-Control-Allow-Methods" "GET, POST, PUT, DELETE, OPTIONS"
                 "Access-Control-Allow-Headers" "Content-Type, Baggage, Sentry-Trace"
                 "Access-Control-Max-Age" "86400"
                 "Vary" "Origin"}]
    (.set (.-headers resp) k v))
  resp)

(defn not-found []
  (js/Response. nil #js {:status 404 :headers #js {"Content-Type" "application/json"}}))

(def router (Router.))

(.options router "/p/:id" (fn [req env] (with-cors env (js/Response. nil))))

(.get router "/p/:id"
      (fn [req env]
        (-> (.get (.-UIXP env) (.. req -params -id))
            (.then t/read)
            (.then (fn [object]
                     (if object
                       (do
                         (dissoc object :token)
                         (with-cors env (not-found)))
                       (with-cors env (not-found))))))))

(defn handle-request [req env ctx]
  (let [url (js/URL. (.-url req))]
    (cond

      (= "c" (.-pathname url))
      (with-cors env (js/Response. nil #js {:status 200}))

      :else (.handle router req env))))

(def handler #js {:fetch handle-request})
