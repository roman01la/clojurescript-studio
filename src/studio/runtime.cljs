(ns studio.runtime
  (:require [cljs.env :as env]
            [cljs.js :as cljs]
            [shadow.cljs.bootstrap.browser :as boot]))

(defonce compile-state-ref (env/default-compiler-env))

(defn eval-cljs [uri]
  (-> (js/fetch uri)
      (.then #(.text %))
      (.then
        #(cljs/eval-str
           compile-state-ref
           %
           "[studio]"
           {:eval cljs/js-eval
            :load (partial boot/load compile-state-ref)}
           (fn [{:keys [error]}]
             (when error
               (js/console.error error)))))))

(defn run-tests []
  (eval-cljs "/test/promesa.cljs")
  (eval-cljs "/test/reagent.cljs")
  (eval-cljs "/test/re_frame.cljs"))

(boot/init compile-state-ref {:path "/out/bootstrap"} run-tests)
