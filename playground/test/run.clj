(ns run
  (:require [clojure.test :refer :all]
            [uix.playground.file-tree-editor-test]))

(defn -main [& args]
  (run-tests 'uix.playground.file-tree-editor-test))
