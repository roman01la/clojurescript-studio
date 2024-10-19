(ns uix.playground.build-hook
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [shadow.build.resolve :as res]
            [shadow.cljs.util :as util]
            [cognitect.transit :as t]
            [uix.playground.macros :as macros]
            [uix.playground.home-shared :as home-shared]
            [uix.core :refer [$]]
            [uix.dom.server :as dom.server]))

(defn- integrity [path]
  (str "sha384-" (:out (sh/sh "bash" "-c" (str "cat " path " | openssl dgst -sha384 -binary | openssl base64 -A")))))

(defn- rewrite-html [path scripts]
  (let [f (with-open [reader (io/reader path)]
            (->> (line-seq reader)
                 (map (fn [s]
                        (if-let [script (some #(when (str/includes? s %) %) scripts)]
                          (str "    <script src=" script " integrity=" (integrity (str "resources/public" script)) "></script>")
                          s)))
                 (str/join "\n")))]
    (spit path f)))

(defn integrity-playground
  {:shadow.build/stage :flush}
  [state]
  (rewrite-html "resources/public/index.html" #{#_"/out/shared.js" "/out/main.js"})
  (rewrite-html "resources/public/project.html" #{#_"/out/shared.js" "/out/main.js"})
  (let [f (with-open [reader (io/reader "resources/public/index.html")]
            (->> (line-seq reader)
                 (map (fn [s]
                        (if (str/includes? s "    <div id=root>")
                          (str "    <div id=root>"
                               (-> (dom.server/render-to-static-markup ($ home-shared/home))
                                   (str/replace #"\n" ""))
                               "</div>")
                          s)))
                 (str/join "\n")))]
    (spit "resources/public/index.html" f))
  (let [f (with-open [reader (io/reader "resources/public/project.html")]
            (->> (line-seq reader)
                 (map (fn [s]
                        (if (str/includes? s "    <div id=root>")
                          (str "    <div id=root></div>")
                          s)))
                 (str/join "\n")))]
    (spit "resources/public/project.html" f))
  state)

(defn integrity-embed
  {:shadow.build/stage :flush}
  [state]
  (rewrite-html "resources/public/embed.html" #{"/out/embed.js"})
  state)

(defn integrity-editor-embed
  {:shadow.build/stage :flush}
  [state]
  (rewrite-html "resources/public/editor_embed.html" #{"/out/editor_embed.js"})
  state)

(defn integrity-runtime
  {:shadow.build/stage :flush}
  [state]
  (rewrite-html "resources/public/sandbox.html" #{"/out/runtime.js"})
  state)

(defn configure-sentry
  {:shadow.build/stage :flush}
  [state]
  (let [release? (= :release (:shadow.build/mode state))
        version (str/trim (macros/get-version*))
        f1 (with-open [reader (io/reader "resources/public/index.html")]
             (->> (line-seq reader)
                  (map (fn [s]
                         (cond
                           (str/includes? s "environment:")
                           (str "          environment: \"" (if release? "prod" "dev") "\",")

                           (str/includes? s "release:")
                           (str "          release: \"" version "\",")

                           (str/includes? s "enabled:")
                           (str "          enabled: " release? ",")

                           :else s)))
                  (str/join "\n")))
        f2 (with-open [reader (io/reader "resources/public/project.html")]
             (->> (line-seq reader)
                  (map (fn [s]
                         (cond
                           (str/includes? s "environment:")
                           (str "          environment: \"" (if release? "prod" "dev") "\",")

                           (str/includes? s "release:")
                           (str "          release: \"" version "\",")

                           (str/includes? s "enabled:")
                           (str "          enabled: " release? ",")

                           :else s)))
                  (str/join "\n")))]
    (spit "resources/public/index.html" f1)
    (spit "resources/public/project.html" f2)
    (spit ".release" version))
  state)

(defn dump-compiler-state
  {:shadow.build/stage :flush}
  [state]
  (let [npm-file->ns (->> state :compiler-env :shadow/js-namespaces vals
                          (reduce (fn [ret {:keys [ns resource-id]}]
                                    (assoc ret
                                      (-> (peek resource-id)
                                          (str/replace #"^node_modules/" "")
                                          (str/replace #"\.js$" ""))
                                      ns))
                                  {}))
        file->ns (->> state :compiler-env :shadow/js-namespaces vals
                      (reduce (fn [ret {:keys [ns resource-id]}]
                                (assoc ret
                                  (peek resource-id)
                                  ns))
                              {}))
        npm-packages (-> state :npm :index-ref deref :packages keys)
        npm-package->ns (->> npm-packages
                             (reduce
                               (fn [ret entry]
                                 (try
                                   (assoc ret entry
                                              (-> state
                                                  (assoc
                                                    :resolved-set #{}
                                                    :resolved-order []
                                                    :resolved-stack [])
                                                  (util/reduce-> res/resolve-entry [entry])
                                                  :resolved-order
                                                  peek peek
                                                  file->ns))
                                   (catch Exception e
                                     ret)))
                               {}))]
    (with-open [out (io/output-stream "resources/public/index.transit")]
      (t/write (t/writer out :json) (into npm-package->ns npm-file->ns))))
  state)
