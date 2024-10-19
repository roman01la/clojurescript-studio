(ns uix.playground.file-tree-editor-test
  (:require [clojure.test :refer :all]
            [uix.playground.file-tree-editor :as edit]))

(def fs
  {"root" {:name "root"
           :path "root"
           :type :folder
           :state/opened? true}
   "root/src" {:type :folder
               :state/opened? true
               :name "src"
               :path "root/src"}})

(deftest test-fs-ops
  (testing "should add tmp file"
    (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
            "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
            "root/src/*" {:type :file, :name nil, :ui/state :name/not-set, :path "root/src/*"}}
           (edit/add-tmp-file fs (fs "root/src")))))

  (testing "should add tmp folder"
    (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
            "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
            "root/src/*" {:type :folder, :name nil, :ui/state :name/not-set, :state/opened? false, :path "root/src/*"}}
           (edit/add-tmp-folder fs (fs "root/src")))))

  (testing "given tmp folder should create a folder"
    (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
            "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
            "root/src/app" {:type :folder, :name "app", :state/opened? false, :path "root/src/app"}}
           (let [fs (edit/add-tmp-folder fs (fs "root/src"))]
             (edit/add-folder fs (fs "root/src/*") "app")))))

  (testing "given tmp file should create folder structure"
    (let [fs (edit/add-tmp-file fs (fs "root/src"))
          tmp-node (get fs "root/src/*")
          fs (edit/add-namespace fs tmp-node "app.core" "(inc 1)")]
      (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
              "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
              "root/src/app/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/core.cljs" :content "(inc 1)"},
              "root/src/app" {:type :folder, :name "app", :path "root/src/app", :state/opened? true}}
             fs))))

  (testing "given tmp file should create folder structure and correctly merge shared paths"
    (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
            "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
            "root/src/app/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/core.cljs" :content "(inc 1)"},
            "root/src/app" {:type :folder, :name "app", :path "root/src/app", :state/opened? true},
            "root/src/app/editor/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/editor/core.cljs" :content "(inc 1)"},
            "root/src/app/editor" {:type :folder, :name "editor", :path "root/src/app/editor", :state/opened? true},
            "root/src/app/editor.cljs" {:type :file, :name "editor.cljs", :path "root/src/app/editor.cljs" :content "(inc 1)"}}
           (->> [["root/src" "app.core"]
                 ["root/src/app" "editor.core"]
                 ["root/src" "app.editor"]]
                (reduce (fn [fs [loc ns-name]]
                          (let [fs (edit/add-tmp-file fs (fs loc))
                                tmp-node (fs (str loc "/*"))]
                            (edit/add-namespace fs tmp-node ns-name "(inc 1)")))
                        fs)))))

  (testing "should rename file"
    (let [fs {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
              "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
              "root/src/app/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/core.cljs"},
              "root/src/app" {:type :folder, :name "app", :path "root/src/app", :state/opened? true}}
          fs (edit/set-file-renaming fs (fs "root/src/app/core.cljs"))
          fs1 (edit/rename-file fs (fs "root/src/app/core.cljs") "impl")
          fs (edit/set-file-renaming fs1 (fs1 "root/src/app/impl.cljs"))
          fs2 (edit/rename-file fs (fs "root/src/app/impl.cljs") "core.cljs")]
      (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
              "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
              "root/src/app" {:type :folder, :name "app", :path "root/src/app", :state/opened? true},
              "root/src/app/impl.cljs" {:type :file, :name "impl.cljs", :path "root/src/app/impl.cljs"}}
             fs1))
      (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
              "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
              "root/src/app" {:type :folder, :name "app", :path "root/src/app", :state/opened? true},
              "root/src/app/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/core.cljs"}}
             fs2))))

  (testing "should rename folder and rewrite descendant paths"
    (let [fs {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
              "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
              "root/src/app/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/core.cljs"},
              "root/src/app" {:type :folder, :name "app", :path "root/src/app", :state/opened? true},
              "root/src/app/editor/core.cljs" {:type :file, :name "core.cljs", :path "root/src/app/editor/core.cljs"},
              "root/src/app/editor" {:type :folder, :name "editor", :path "root/src/app/editor", :state/opened? true},
              "root/src/app/editor.cljs" {:type :file, :name "editor.cljs", :path "root/src/app/editor.cljs"}}
          fs (edit/set-file-renaming fs (fs "root/src/app"))
          fs (edit/rename-folder fs (fs "root/src/app") "old")]
      (is (= {"root" {:name "root", :path "root", :type :folder, :state/opened? true},
              "root/src" {:type :folder, :state/opened? true, :name "src", :path "root/src"},
              "root/src/old/core.cljs" {:type :file, :name "core.cljs", :path "root/src/old/core.cljs"},
              "root/src/old" {:type :folder, :name "old", :path "root/src/old", :state/opened? true},
              "root/src/old/editor/core.cljs" {:type :file, :name "core.cljs", :path "root/src/old/editor/core.cljs"},
              "root/src/old/editor" {:type :folder, :name "editor", :path "root/src/old/editor", :state/opened? true},
              "root/src/old/editor.cljs" {:type :file, :name "editor.cljs", :path "root/src/old/editor.cljs"}}
             fs)))))
