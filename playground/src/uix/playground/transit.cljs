(ns uix.playground.transit
  (:require [cognitect.transit :as t]
            [com.cognitect.transit :as ct]
            [com.cognitect.transit.types :as ty]))

(deftype ObjectHandler []
  Object
  (tag [_ v] "obj")
  (rep [_ v]
    (let [ret #js []]
      (doseq [x (js/Object.entries v)]
        (.push ret (aget x 0) (aget x 1)))
      (ct/tagged "array" ret)))
  (stringRep [_ v] nil))

(deftype ArrayHandler []
  Object
  (tag [_ v] "arr")
  (rep [_ v]
    (ct/tagged "array" v))
  (stringRep [_ v] nil))

(defn- kw-coll->obj [coll idx]
  (let [ret #js {}]
    (loop [idx idx]
      (when (< idx (.-length coll))
        (aset ret (aget coll idx) (aget coll (inc idx)))
        (recur (+ idx 2))))
    ret))

(def read-handlers
  {"u" cljs.core/uuid
   "obj" (fn [v]
           (kw-coll->obj v 0))
   "arr" (fn [v] v)})

(def write-handlers
  {js/Object (ObjectHandler.)
   js/Array (ArrayHandler.)})

(def reader
  (t/reader :json {:handlers read-handlers}))

(def writer
  (t/writer :json {:handlers write-handlers}))

(defn write [payload]
  (t/write writer payload))

(defn read [payload]
  (t/read reader payload))

(extend-type ty/UUID cljs.core/IUUID)
