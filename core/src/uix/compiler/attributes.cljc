(ns uix.compiler.attributes
  (:require [clojure.string :as str]
            #?(:cljs [goog.object :as gobj])))

(def re-tag
  "HyperScript tag pattern :div :div#id.class etc."
  #"([^\.#]*)(?:#([^\.#]+))?(?:\.([^#]+))?")

(defn parse-tag
  "Takes HyperScript tag (:div#id.class) and returns parsed tag, id and class fields,
  and boolean indicating if tag name is a custom element (a custom DOM element that has hyphen in the name)"
  [tag]
  (let [tag-str (name tag)]
    (when (and (not (re-matches re-tag tag-str))
               (re-find #"[#\.]" tag-str))
      ;; Throwing NPE here because shadow catches those to bring up error view in a browser
      #?(:clj (throw (NullPointerException. (str "Invalid tag name (found: " tag-str "). Make sure that the name matches the format and ordering is correct `:tag#id.class`")))
         :cljs (throw (js/Error. (str "Invalid tag name (found: " tag-str "). Make sure that the name matches the format and ordering is correct `:tag#id.class`")))))
    (let [[tag id class-name] (next (re-matches re-tag tag-str))
          tag (if (= "" tag) "div" tag)
          class-name (when-not (nil? class-name)
                       (str/replace class-name #"\." " "))]
      (list tag id class-name (some? (re-find #"-" tag))))))

(defn set-id-class
  "Takes attributes map and parsed tag triplet,
  and returns attributes merged with class names and id"
  [props [_ id class]]
  (let [props-class (get props :class)]
    (cond-> props
            ;; Only use ID from tag keyword if no :id in props already
      (and (some? id) (nil? (get props :id)))
      (assoc :id id)

              ;; Merge classes
      (or class props-class)
      (assoc :class (cond
                      (vector? props-class) `(class-names ~class ~@props-class)
                      props-class `(class-names ~class ~props-class)
                      :else class)))))

(defn camel-case-dom
  "Turns kebab-case keyword into camel-case keyword,
  kebab-cased DOM attributes aria-* and data-* are not converted"
  [k]
  (if (keyword? k)
    (let [[first-word & words] (str/split (name k) #"-")]
      (if (or (empty? words)
              (= "aria" first-word)
              (= "data" first-word))
        k
        (-> (map str/capitalize words)
            (conj first-word)
            str/join
            keyword)))
    k))

(defn camel-case-keys
  "Takes map of attributes and returns same map with camel-cased keys"
  [m]
  (if (map? m)
    (reduce-kv #(assoc %1 (camel-case-dom %2) %3) {} m)
    m))

(defn convert-value
  ([v]
   (if (or (symbol? v) (list? v))
     `(keyword->string ~v)
     v))
  ([k v]
   (cond
     (= :children v) v
     (str/starts-with? (name k) "on-") v
     (symbol? v) `(keyword->string ~v)
     :else v)))

(defn convert-values [m]
  (if (map? m)
    (reduce-kv #(assoc %1 (camel-case-dom %2) (convert-value %3)) {} m)
    m))

(defmulti compile-config-kv (fn [name value] name))

(defmethod compile-config-kv :style [name value]
  (convert-values (camel-case-keys value)))

(defmethod compile-config-kv :default [name value]
  (convert-value name value))

(defn compile-attrs
  "Takes map of attributes and returns same map with keys
  translated from Clojure to React naming conventions

  :class -> :className
  :margin-right -> :marginRight
  :on-click -> :onClick"
  ([attrs]
   (compile-attrs attrs nil))
  ([attrs {:keys [custom-element?]}]
   (if (map? attrs)
     (reduce-kv
      #(assoc %1
              (if custom-element?
                (camel-case-dom %2)
                (case %2
                  :class :className
                  :for :htmlFor
                  :charset :charSet
                  :class-id :classID
                  :item-id :itemID
                  (camel-case-dom %2)))
              (compile-config-kv %2 %3))
      {}
      attrs)
     attrs)))

#?(:cljs
   (do
     (declare convert-prop-value)
     (declare convert-prop-value-shallow)

     (defn js-val? [x]
       (not (identical? "object" (goog/typeOf x))))

     (def prop-name-cache
       #js {:class "className"
            :for "htmlFor"
            :charset "charSet"
            :class-id "classID"
            :item-id "itemID"})

     (def custom-prop-name-cache #js {})

     (def ^:private cc-regexp (js/RegExp. "-(\\w)" "g"))

     (defn- cc-fn [s]
       (str/upper-case (aget s 1)))

     (defn ^string dash-to-camel [^string name-str]
       (if (or (str/starts-with? name-str "aria-")
               (str/starts-with? name-str "data-"))
         name-str
         (.replace name-str cc-regexp cc-fn)))

     (defn keyword->string [x]
       (if (keyword? x)
         (-name ^not-native x)
         x))

     (defn cached-prop-name [k]
       (if (keyword? k)
         (let [name-str (-name ^not-native k)]
           (if-some [k' (aget prop-name-cache name-str)]
             k'
             (let [v (dash-to-camel name-str)]
               (aset prop-name-cache name-str v)
               v)))
         k))

     (defn cached-custom-prop-name [k]
       (if (keyword? k)
         (let [name-str (-name ^not-native k)]
           (if-some [k' (aget custom-prop-name-cache name-str)]
             k'
             (let [v (dash-to-camel name-str)]
               (aset custom-prop-name-cache name-str v)
               v)))
         k))

     (defn convert-interop-prop-value [k v]
       (cond
         (= k :style) (if (vector? v)
                        (-reduce ^not-native v
                                 (fn [a v]
                                   (.push a (convert-prop-value-shallow v))
                                   a)
                                 #js [])
                        (convert-prop-value-shallow v))
         (keyword? v) (-name ^not-native v)
         :else v))

     (defn kv-conv [o k v]
       (gobj/set o (cached-prop-name k) (convert-prop-value v))
       o)

     (defn kv-conv-shallow [o k v]
       (gobj/set o (cached-prop-name k) (convert-interop-prop-value k v))
       o)

     (defn custom-kv-conv [o k v]
       (gobj/set o (cached-custom-prop-name k) (convert-prop-value v))
       o)

     (defn convert-prop-value [x]
       (cond
         (js-val? x) x
         (keyword? x) (-name ^not-native x)
         (map? x) (reduce-kv kv-conv #js {} x)
         (coll? x) (clj->js x)
         (ifn? x) #(apply x %&)
         :else (clj->js x)))

     (defn convert-custom-prop-value [x]
       (cond
         (js-val? x) x
         (keyword? x) (-name ^not-native x)
         (map? x) (reduce-kv custom-kv-conv #js {} x)
         (coll? x) (clj->js x)
         (ifn? x) #(apply x %&)
         :else (clj->js x)))

     (defn convert-prop-value-shallow [x]
       (if (map? x)
         (reduce-kv kv-conv-shallow #js {} x)
         x))

     (declare class-names)

     (defn class-names-coll [classes]
       (let [^js/Array classes (reduce (fn [^js/Array a c]
                                         (when ^boolean c
                                           (->> (if (keyword? c) (-name ^not-native c) (class-names c))
                                                (.push a)))
                                         a)
                                       #js []
                                       classes)]
         (when (pos? (.-length classes))
           (.join classes " "))))

     (defn ^string class-names
       "Merges a collection of class names into a string"
       ([a]
        (cond
          (or (array? a) (coll? a)) (class-names-coll a)
          (keyword? a) (-name ^not-native a)
          :else a))
       ([a b]
        (if ^boolean a
          (if ^boolean b
            (str (class-names a) " " (class-names b))
            (class-names a))
          (class-names b)))
       ([a b & rst]
        (reduce class-names (class-names a b) rst)))

     (defn parse-tag-runtime
       "Takes HyperScript tag (:div#id.class) and returns parsed tag, id and class fields,
       and boolean indicating if tag name is a custom element (a custom DOM element that has hyphen in the name)"
       [tag]
       (let [tag-str (name tag)]
         (when (and (not (re-matches re-tag tag-str))
                    (re-find #"[#\.]" tag-str))
           ;; Throwing NPE here because shadow catches those to bring up error view in a browser
           (throw (js/Error. (str "Invalid tag name (found: " tag-str "). Make sure that the name matches the format and ordering is correct `:tag#id.class`"))))
         (let [[tag id class-name] (next (re-matches re-tag tag-str))
               tag (if (= "" tag) "div" tag)
               class-name (when-not (nil? class-name)
                            (str/replace class-name #"\." " "))]
           #js [tag id class-name (some? (re-find #"-" tag))])))

     (defn- set-id-class-runtime
       "Takes attributes map and parsed tag, and returns attributes merged with class names and id"
       [props id-class]
       (let [props-class (get props :class)
             id (aget id-class 1)
             class (aget id-class 2)]
         (cond-> props
                 ;; Only use ID from tag keyword if no :id in props already
                 (and (some? id) (nil? (get props :id)))
                 (assoc :id id)

                 ;; Merge classes
                 (or class props-class)
                 (assoc :class (class-names class props-class)))))

     (defn ^js convert-props
       "Converts `props` Clojure map into JS object suitable for
       passing as `props` object into `React.createElement`

       - `props` — Clojure map of props
       - `id-class` — a triplet of parsed tag, id and class names
       - `shallow?` — indicates whether `props` map should be converted shallowly or not"
       [props id-class ^boolean shallow?]
       (let [props (set-id-class-runtime props id-class)]
         (cond
           ^boolean (aget id-class 3)
           (convert-custom-prop-value props)

           shallow?
           (convert-prop-value-shallow props)

           :else (convert-prop-value props))))

     (defn interpret-attrs
       "Returns a tuple of attributes and a child element

       - [attrs] when `attrs` is actually a map of attributes
       - [nil attrs] when `attrs` is not a map, thus a child element"
       [maybe-attrs id-class shallow?]
       (if (map? maybe-attrs)
         #js [(convert-props maybe-attrs id-class shallow?)]
         #js [(convert-props {} id-class shallow?) maybe-attrs]))

     (defn interpret-props
       "Returns a tuple of component props and a child element

       - [props] when `props` is actually a map of attributes
       - [nil props] when `props` is not a map, thus a child element"
       [props]
       (if (map? props)
         #js [props]
         #js [nil props]))))
