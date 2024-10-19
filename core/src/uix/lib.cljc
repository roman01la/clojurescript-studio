(ns uix.lib
  #?(:cljs (:require-macros [uix.lib]))
  #?(:cljs (:require [goog.object :as gobj]
                     [clojure.walk]))
  #?(:clj (:require [clojure.walk]
                    [cljs.core]))
  #?(:clj (:import (clojure.lang RT Util))))

(defn assert! [x message]
  (when-not x
    #?(:cljs (throw (js/Error. (str "Assert failed: " message "\n" (pr-str x)))))
    #?(:clj (throw (AssertionError. (str "Assert failed: " message "\n" (pr-str x)))))))

(defmacro doseq-loop [[v vs] & body]
  `(let [v# ~vs]
     (when (seq v#)
       (loop [x# (first v#)
              xs# (next v#)]
         (let [~v x#]
           ~@body)
         (when (seq xs#)
           (recur (first xs#) (next xs#)))))))

#?(:cljs
   (defn map->js [m]
     (reduce-kv (fn [o k v]
                  (gobj/set o (name k) v)
                  o)
                #js {}
                m)))

(defn cljs-env? [env]
  (boolean (:ns env)))

(defn find-form [pred sexp]
  (let [forms (atom [])]
    (clojure.walk/prewalk
     (fn [x]
       (when (pred x)
         (swap! forms conj x))
       x)
     sexp)
    @forms))

(def
  ^{:private true}
  sigs
  (fn [fdecl]
   #_(assert-valid-fdecl fdecl)
   (let [asig
        (fn [fdecl]
                 (let [arglist (first fdecl)
                            ;elide implicit macro args
                            arglist (if #?(:clj  (Util/equals '&form (first arglist))
                                           :cljs (= '&form (first arglist)))
                                      #?(:clj  (RT/subvec arglist 2 (RT/count arglist))
                                         :cljs (subvec arglist 2 (count arglist)))
                                      arglist)
                            body (next fdecl)]
                           (if (map? (first body))
                             (if (next body)
                               (with-meta arglist (conj (if (meta arglist) (meta arglist) {}) (first body)))
                               arglist)
                             arglist)))]
       (if (seq? (first fdecl))
         (loop [ret [] fdecls fdecl]
                    (if fdecls
                      (recur (conj ret (asig (first fdecls))) (next fdecls))
                      (seq ret)))
         (list (asig fdecl))))))


(defn parse-sig [name fdecl]
  (let [[fdecl m] (if (string? (first fdecl))
                    [(next fdecl) {:doc (first fdecl)}]
                    [fdecl {}])
        [fdecl m] (if (map? (first fdecl))
                    [(next fdecl) (conj m (first fdecl))]
                    [fdecl m])
        fdecl (if (vector? (first fdecl))
                (list fdecl)
                fdecl)
        [fdecl m] (if (map? (last fdecl))
                    [(butlast fdecl) (conj m (last fdecl))]
                    [fdecl m])
        m (conj {:arglists (list 'quote (sigs fdecl))} m)
        m (conj (if (meta name) (meta name) {}) m)]
    [(with-meta name m) fdecl]))
