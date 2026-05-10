(ns mandy.main
  (:require [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [yamlscript.core :as ys])
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

(defn strip-troff [line]
  (-> line
      (str/replace #"^\.[A-Z]{1,10}(\s+|$)" "") ; Strip leading capital macros (e.g., .PP, .INDENT, .TP)
      (str/replace #"^\.[a-z]{1,2}(\s+|$)" "")  ; Strip leading lowercase macros (e.g., .in, .br, .sp)
      (str/replace #"^\.\s*$" "")               ; Strip standalone dots
      (str/replace #"\.IX\s+.*" "")             ; Strip indexing macros and their entire line
      (str/replace #"\.[IB][IRP]?\s+" "")       ; Strip common mdoc/man font macros (inline-style)
      (str/replace #"\.(It|Ic|Ar|Fl)\s+" "")   ; Strip mdoc .It, .Ic, .Ar, .Fl
      (str/replace #"\\f[BIRP]" "")             ; Strip inline font changes \fB, \fI, etc.
      (str/replace #"\\s[+-]?\d+" "")           ; Strip inline size changes \sN, \s+N
      (str/replace #"\\\*(\([a-zA-Z0-9]{2}|\[[a-zA-Z0-9]+\]|.)" "") ; Strip \* interpolation
      (str/replace #"\\n(\([a-zA-Z0-9]{2}|\[[a-zA-Z0-9]+\]|.)" "") ; Strip \n interpolation
      (str/replace #"\\&" "")                   ; Strip non-printing &
      (str/replace #"\\\(.." "")               ; Strip complex characters like \(bu
      (str/replace #"\\." "")))                 ; Strip remaining backslash escapes

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
  (println "🌿 Mandy - Manual Discovery Tool (Beta 0.1)")
  (println "")
  (println "Usage:")
  (println "  mandy <command>                 Start interactive TUI")
  (println "  mandy <command> | cat           Output raw YAMLScript data")
  (println "  mandy -k <keyword>              CLI Deep Search across all manuals")
  (println "  mandy -K <keyword>              TUI Deep Search across all manuals")
  (println "  mandy <file.md>                 Render local Markdown via pandoc")
  (println "")
  (println "Discovery Options:")
  (println "  -c, --context <string>    Search for command variants matching <string>")
  (println "  -A, --after-context <n>   Number of context lines to include after variants")
  (println "  --json                    Output results as a JSON array or object")
  (println "")
  (println "General Options:")
  (println "  -d, --debug               Output raw YAMLScript and exit")
  (println "  -s, --strip               Output sanitized YAMLScript (condensed whitespace)")
  (println "  -h, --help                Show this help message"))

(defn run-discovery [command args is-debug is-strip is-tty]
  (let [man-cmd (let [file (io/file command)
                      is-file (and (.exists file) (.isFile file))
                      is-md (and is-file (str/ends-with? command ".md"))]
                  (cond
                    is-md (let [pandoc-check (sh "which" "pandoc")]
                            (if (zero? (:exit pandoc-check))
                              (str "pandoc -s -t man " command " | man -l - | col -b")
                              (do (binding [*out* *err*]
                                    (println "Error: 'pandoc' is required to parse Markdown files. Please install it first."))
                                  (System/exit 1))))
                    is-file (str "man -l " command " | col -b")
                    :else (str "man " command " | col -b")))
        man-raw (try 
                  (let [raw (:out (sh "bash" "-c" man-cmd))]
                    (str/replace raw "\t" "        "))
                  (catch Exception e 
                    (binding [*out* *err*] (println "Error: Command or file not found"))
                    (System/exit 1)))
        
        ;; Use the embedded YAMLScript engine for parsing
        mandy-root (or (System/getenv "MANDY_ROOT") "/home/aaron/dev/mandy")
        base-plugin-res (io/resource "base.ys")
        base-plugin-file (io/file mandy-root "plugins/base.ys")
        structured-data (try
                          (let [plugin-content (if base-plugin-res
                                                 (slurp base-plugin-res)
                                                 (slurp base-plugin-file))]
                            (ys/load plugin-content {"input-text" man-raw}))
                          (catch Exception e
                            (binding [*out* *err*] (println "Error: YAMLScript parsing failed:" (.getMessage e)))
                            ;; Fallback to internal Clojure logic if YS fails
                            (parse-man (map-indexed vector (str/split-lines man-raw)))))
        
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
            ;; Locate TUI binary relative to the JAR or fall back to dev path
            jar-path (-> (System/getProperty "java.class.path") 
                         (str/split (re-pattern (System/getProperty "path.separator"))) 
                         first io/file .getAbsoluteFile .getParent)
            mandy-ui-bin (io/file jar-path "mandy-ui")
            tui-cmd (if (.exists mandy-ui-bin)
                      [(.getAbsolutePath mandy-ui-bin)]
                      ["bun" "run" (str (or (System/getenv "MANDY_ROOT") "/home/aaron/dev/mandy") "/src/index.ts")])
            pb (ProcessBuilder. (into tui-cmd args))
            env (.environment pb)
            _ (.put env "MANDY_PAYLOAD_PATH" (.getAbsolutePath tmp-file))
            _ (.redirectInput pb java.lang.ProcessBuilder$Redirect/INHERIT)
            _ (.redirectError pb java.lang.ProcessBuilder$Redirect/INHERIT)
            _ (.redirectOutput pb java.lang.ProcessBuilder$Redirect/INHERIT)
            proc (.start pb)]
        (.waitFor proc)
        (.delete tmp-file)))))

(defn -main [& args]
  (let [is-help (some #{"--help" "-h"} args)
        is-debug (some #{"--debug" "-d"} args)
        is-strip (some #{"--strip" "-s"} args)
        is-json (some #{"--json"} args)
        is-tty (not (nil? (System/console)))
        
        ;; Deep Search Flags
        is-deep-cli (some #{"-k"} args)
        is-deep-tui (some #{"-K"} args)
        
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

    (if (or is-deep-cli is-deep-tui)
      (let [keyword command
            _ (when-not keyword (do (println "Error: No keyword provided for deep search.") (System/exit 1)))
            _ (binding [*out* *err*] (print (str "Searching for \"" keyword "\"... ")) (flush))
            search-all? (System/getenv "MANDY_ALL_SECTIONS")
            section-flag (if search-all? "" "-S 1:6:8 ")
            search-cmd (str "man " section-flag "-wK \"" keyword "\" 2>/dev/null")
            results-raw (:out (sh "bash" "-c" search-cmd))
            _ (binding [*out* *err*] (println "Done."))
            paths (->> (str/split-lines results-raw)
                       (remove str/blank?)
                       distinct)
            results (for [path paths]
                      (let [filename (last (str/split path #"/"))
                            cmd-name (first (str/split filename #"\."))
                            context-raw (:out (sh "bash" "-c" (str "zgrep -i -m 1 -C 1 \"" keyword "\" " path " 2>/dev/null")))
                            context (->> (str/split-lines context-raw)
                                         (map strip-troff)
                                         (map sanitize)
                                         (remove str/blank?))]
                        [cmd-name {:path path :context context}]))
            unique-results (->> results
                                (group-by first)
                                (map (fn [[_ group]] (first group)))
                                (into (array-map)))]
        (cond
          (empty? unique-results)
          (do (println "No results found.") (System/exit 0))

          (= 1 (count unique-results))
          (let [match (first (keys unique-results))]
            (if is-deep-cli
              (run-discovery match [] true false is-tty)
              (run-discovery match [] false false is-tty)))

          :else
          (do (println "!yamlscript/v0/data")
              (println (yaml/generate-string {:RESULTS unique-results})))))
      
      (if-not command
        (do (println "Error: No command specified.")
            (System/exit 1))
        
        (if (or context-string after-n)
          ;; Context-based discovery mode
          (let [man-cmd (str "man " command " | col -b")
                man-raw (:out (sh "bash" "-c" man-cmd))
                
                mandy-root (or (System/getenv "MANDY_ROOT") "/home/aaron/dev/mandy")
                base-plugin-res (io/resource "base.ys")
                base-plugin-file (io/file mandy-root "plugins/base.ys")
                structured-data (try
                                  (let [plugin-content (if base-plugin-res
                                                         (slurp base-plugin-res)
                                                         (slurp base-plugin-file))]
                                    (ys/load plugin-content {"input-text" man-raw}))
                                  (catch Exception e
                                    (parse-man (map-indexed vector (str/split-lines man-raw)))))
                
                lines (str/split-lines man-raw)
                results (if context-string
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

          (run-discovery command args is-debug is-strip is-tty))))
    (System/exit 0)))
