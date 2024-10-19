(ns uix.test-utils
  (:require ["react-dom/server" :as rserver]
            ["react-dom/client" :as rdc]
            ["react-dom/test-utils" :as rdt]
            [goog.object :as gobj]
            [clojure.test :refer [is]]
            [uix.dom :as dom]))

(defn as-string [el]
  (rserver/renderToStaticMarkup el))

(defn js-equal? [a b]
  (gobj/equals a b))

(defn symbol-for [s]
  (js* "Symbol.for(~{})" s))

(defn react-element-of-type? [f type]
  (= (gobj/get f "$$typeof") (symbol-for type)))

(defn with-error [f]
  (let [msgs (atom [])
        cc js/console.error]
    (set! js/console.error #(swap! msgs conj %))
    (f)
    (set! js/console.error cc)
    (is (empty? @msgs))))

(defn render [el]
  (let [node (.createElement js/document "div")
        _ (.append (.getElementById js/document "root") node)
        root (dom/create-root node)]
    (dom/render-root el root)))

(defn with-react-root
  ([el f]
   (with-react-root el f (fn [])))
  ([el f after-unmount]
   (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
   (let [node (js/document.createElement "div")
         _ (js/document.body.append node)
         root (rdc/createRoot node)]
     (rdt/act #(.render root el))
     (f node)
     (rdt/act #(.unmount root))
     (after-unmount)
     (.remove node)
     (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false))))
