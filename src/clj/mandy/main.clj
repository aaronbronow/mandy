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

(defn extract-tokens [line]
  (map second (re-seq #"(?:^|\s|,)(-{1,2}[a-zA-Z0-9-]+)" line)))

(defn find-variants [command structured-data context-string all-lines n]
  (let [pattern (re-pattern (str "(?i)\\b" context-string "\\b"))]
    (->> structured-data
         (mapcat (fn [section]
                   (let [[heading line-maps] (first section)]
                     (if (#{"SYNOPSIS" "PREFACE"} heading)
                       []
                       (:matches
                        (reduce (fn [acc lm]
                                  (let [tokens (extract-tokens (:text lm))
                                        current-state (if (seq tokens)
                                                       {:tokens tokens :line-idx (:originalIndex lm)}
                                                       (:last-tokens acc))]
                                    (if (re-find pattern (:text lm))
                                      (-> acc
                                          (update :matches conj current-state)
                                          (assoc :last-tokens current-state))
                                      (assoc acc :last-tokens current-state))))
                                {:matches [] :last-tokens {:tokens [] :line-idx -1}}
                                line-maps))))))
         (mapcat (fn [{:keys [tokens line-idx]}]
                   (map (fn [token]
                          {:variant (str command " " token)
                           :context (if (and n (>= line-idx 0))
                                      (take n (drop (inc line-idx) all-lines))
                                      [])})
                        tokens)))
         distinct)))

(defn display-help []
  (println "🌿 Mandy - Manual Discovery Tool (Alpha 0.3)")
  (println "")
  (println "Usage:")
  (println "  mandy <command>                 Start interactive TUI")
  (println "  mandy <command> | cat           Output raw YAMLScript data (High Fidelity)")
  (println "  mandy <command> -c <context>    Discover command variants matching context")
  (println "  mandy <command> -A <n>          Include <n> lines of trailing context")
  (println "")
  (println "Discovery Options:")
  (println "  -c, --context <string>    Search for command variants matching <string>")
  (println "  -A, --after-context <n>   Number of context lines to include after variants")
  (println "  --json                    Output results as a JSON array or object")
  (println "")
  (println "General Options:")
  (println "  -d, --debug               Output raw YAMLScript and exit")
  (println "  -s, --strip               Output sanitized YAMLScript (condensed whitespace)")
  (println "  -v, --vim                 Enable VIM keybindings in TUI")
  (println "  -h, --help                Show this help message")
  (println "")
  (println "Agentic Discovery:")
  (println "  For fast, non-interactive discovery, use the -c and -A flags.")
  (println "  Mandy is instant (<10ms) when bypassing the TUI."))

(defn -main [& args]
  (let [is-help (some #{"--help" "-h"} args)
        is-debug (some #{"--debug" "-d"} args)
        is-strip (some #{"--strip" "-s"} args)
        is-json (some #{"--json"} args)
        is-tty (not (nil? (System/console)))
        command (first (remove #(str/starts-with? % "-") args))
        
        ;; Context search parsing
        context-idx (some (fn [[i arg]] (when (#{"-c" "--context"} arg) i)) (map-indexed vector args))
        context-string (when context-idx (nth args (inc context-idx) nil))
        
        ;; After context parsing
        after-idx (some (fn [[i arg]] (when (#{"-A" "--after-context"} arg) i)) (map-indexed vector args))
        after-n (when after-idx (Integer/parseInt (nth args (inc after-idx) "0")))]

    (when (or is-help (empty? args))
      (display-help)
      (System/exit 0))

    (if-not command
      (do (println "Error: No command specified.")
          (println "Usage: mandy <command> [-c context] [-A n] [--json]") 
          (System/exit 1)))

    (let [man-raw (try 
                    (let [raw (:out (sh "bash" "-c" (str "man " command " | col -b")))]
                      (str/replace raw "\t" "        "))
                    (catch Exception e 
                      (binding [*out* *err*] (println "Error: Command not found"))
                      (System/exit 1)))
          
          lines (str/split-lines man-raw)
          indexed-lines (map-indexed vector lines)
          structured-data (parse-man indexed-lines)
          
          raw-yaml (generate-yaml structured-data false)
          sanitized-yaml (generate-yaml structured-data true)]

      (cond
        (System/getenv "MANDY_DRY_RUN")
        (let [payload (json/generate-string 
                       {:rawYaml raw-yaml
                        :sanitizedYaml sanitized-yaml
                        :structuredData structured-data
                        :cmd command})
              tmp-file (java.io.File/createTempFile "mandy-payload-" ".json")
              _ (spit tmp-file payload)]
          (println (.getAbsolutePath tmp-file))
          (System/exit 0))

        (or context-string after-n)
        (let [results (if context-string
                        (find-variants command structured-data context-string lines after-n)
                        [{:variant command :context (if after-n (take after-n lines) [])}])]
          (if is-json
            (println (json/generate-string results))
            (doseq [i (range (count results))]
              (let [{:keys [variant context]} (nth results i)]
                (println variant)
                (doseq [ctx context] (println ctx))
                (when (and after-idx (> (count results) 1) (< i (dec (count results))))
                  (println "--")))))
          (System/exit 0))

        (or is-debug is-strip (not is-tty))
        (println (if is-strip sanitized-yaml raw-yaml))

        :else
        (let [payload (json/generate-string 
                       {:rawYaml raw-yaml
                        :sanitizedYaml sanitized-yaml
                        :structuredData structured-data
                        :cmd command})
              tmp-file (java.io.File/createTempFile "mandy-payload-" ".json")
              _ (spit tmp-file payload)
              pb (ProcessBuilder. (into ["node" "dist/index.js"] args))
              env (.environment pb)
              _ (.put env "MANDY_PAYLOAD_PATH" (.getAbsolutePath tmp-file))
              _ (.redirectInput pb java.lang.ProcessBuilder$Redirect/INHERIT)
              _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
              _ (.redirectOutput pb java.lang.ProcessBuilder$Redirect/INHERIT)
              proc (.start pb)]
          (.waitFor proc)
          (.delete tmp-file))))
    (System/exit 0)))
