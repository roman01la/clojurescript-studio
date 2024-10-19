(ns uix.playground.coll)

(defn map-vals [f m]
  (->> m
       (reduce-kv (fn [m k v] (assoc! m k (f v)))
                  (transient {}))
       persistent!))

(defn map-keys [f m]
  (->> m
       (reduce-kv (fn [m k v] (assoc! m (f k) v))
                  (transient {}))
       persistent!))

(defn find-index [f coll]
  (loop [[x & xs] coll
         idx 0]
    (cond
      (f x) idx
      (seq xs) (recur xs (inc idx)))))
