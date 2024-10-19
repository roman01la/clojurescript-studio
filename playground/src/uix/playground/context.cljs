(ns uix.playground.context
  (:require [uix.core :as uix]))

(def editor (uix/create-context {}))

(def editor-provider (.-Provider editor))
