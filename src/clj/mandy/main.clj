(ns mandy.main
  (:require [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.shell :refer [sh]])
  (:import [java.lang ProcessBuilder]
           [java.lang ProcessBuilder$Redirect])
  (:gen-class))

(defn heading? [line]
  (re-matches #"[A-Z][A-Z0-9()\s]*[A-Z0-9)]" (str/trim line)))

(defn sanitize [line]
  (-> line
      (str/replace "\t" " ")
      (str/replace #"\s+" " ")
      str/trim))

(defn parse-man [indexed-lines]
  (reduce (fn [acc [idx line]]
            (if (heading? line)
              (conj acc {(str/trim line) []})
              (let [last-idx (dec (count acc))
                    heading (first (keys (nth acc last-idx)))
                    lm {:originalIndex idx :text line}]
                (update-in acc [last-idx heading] conj lm))))
          [{"PREFACE" []}]
          indexed-lines))

(defn generate-yaml [structured-data sanitized?]
  (let [header "!yamlscript/v0/data\n"
        yaml-map (reduce (fn [acc section]
                           (let [[heading line-maps] (first section)]
                             (assoc acc heading 
                                    (if sanitized?
                                      (->> line-maps
                                           (map :text)
                                           (map sanitize)
                                           (remove str/blank?))
                                      (map :text line-maps)))))
                         (array-map)
                         structured-data)]
    (str header (yaml/generate-string yaml-map))))

(defn -main [& args]
  (let [is-debug (some #{"--debug" "-d"} args)
        is-strip (some #{"--strip" "-s"} args)
        is-tty (not (nil? (System/console)))
        command (first (remove #(str/starts-with? % "-") args))]

    (if-not command
      (do (println "Usage: mandy <command>") (System/exit 1)))

    (let [man-raw (try 
                    (:out (sh "bash" "-c" (str "man " command " | col -b")))
                    (catch Exception e 
                      (binding [*out* *err*] (println "Error: Command not found"))
                      (System/exit 1)))
          
          lines (str/split-lines man-raw)
          indexed-lines (map-indexed vector lines)
          structured-data (parse-man indexed-lines)
          
          raw-yaml (generate-yaml structured-data false)
          sanitized-yaml (generate-yaml structured-data true)]

      (if (or is-debug is-strip (not is-tty))
        (println (if is-strip sanitized-yaml raw-yaml))
        (let [payload (json/generate-string 
                       {:rawYaml raw-yaml
                        :sanitizedYaml sanitized-yaml
                        :structuredData structured-data})
              pb (ProcessBuilder. ["node" "dist/index.js"])
              _ (.redirectError pb ProcessBuilder$Redirect/INHERIT)
              _ (.redirectOutput pb ProcessBuilder$Redirect/INHERIT)
              proc (.start pb)
              out (.getOutputStream proc)]
          (.write out (.getBytes payload))
          (.flush out)
          (.close out)
          (.waitFor proc))))
    (System/exit 0)))
