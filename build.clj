(ns build
  (:require [clojure.tools.build.api :as b]))

(def lib 'mandy)
(def version "0.1.0")
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def uber-file (format "target/%s-%s-standalone.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn uber [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src/clj" "plugins"]
               :target-dir class-dir})
  (b/compile-clj {:basis basis
                  :src-dirs ["src/clj"]
                  :class-dir class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis basis
           :main 'mandy.main}))

(defn native [_]
  (uber nil)
  (println "Compiling native image...")
  (b/process {:command-args ["native-image"
                             "-jar" uber-file
                             "mandy"
                             "--no-fallback"
                             "--initialize-at-build-time"
                             "-H:IncludeResources=base.ys"
                             "-H:+ReportExceptionStackTraces"]}))
