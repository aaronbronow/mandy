(ns mandy.main
  (:require [clj-yaml.core :as yaml]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [mandy.ubuntu :as ubuntu]
            [mandy.check :as check]
            [mandy.mcp :as mcp])
  (:import [java.lang ProcessBuilder]
           [java.lang ProcessBuilder$Redirect])
  (:gen-class))

(def heading? ubuntu/heading?)
(def sanitize ubuntu/sanitize)
(def strip-troff ubuntu/strip-troff)
(def parse-man ubuntu/parse-man)

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
  (println "🌿 Mandy - Manual Discovery Tool (Beta 0.2 Ubuntu)")
  (println "")
  (println "Usage:")
  (println "  mandy <command>                 Start interactive TUI")
  (println "  mandy <command> | cat           Output raw YAMLScript data")
  (println "  mandy discover [opts]           Live Ubuntu tool discovery (dpkg + man + --help)")
  (println "  mandy check [opts] <cmd>        PreToolUse guardrail check (exit 0 allow, 2 block)")
  (println "  mandy mcp                       Start stdio JSON-RPC 2.0 MCP server")
  (println "  mandy -k <keyword>              CLI Deep Search (Agent-First Ubuntu YAML)")
  (println "  mandy -K <keyword>              TUI Deep Search across all manuals")
  (println "  mandy <file.md>                 Render local Markdown via pandoc")
  (println "")
  (println "Ubuntu Discover Options:")
  (println "  -i, --intent <text>       Natural-language intent for tool discovery")
  (println "  -k, --keyword <term>      Optional binary name or topic keyword")
  (println "  -n, --limit <N>           Max verified candidates (1-5, default 3)")
  (println "  --json                    Output results as JSON")
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

(defn- parse-discover-opts [args]
  (loop [xs args
         opts {:intent "" :keyword nil :limit nil :json? false :help? false}]
    (if (empty? xs)
      opts
      (let [[a b & more] xs]
        (cond
          (#{"-h" "--help"} a)
          (recur (rest xs) (assoc opts :help? true))

          (= "--json" a)
          (recur (rest xs) (assoc opts :json? true))

          (#{"-i" "--intent"} a)
          (recur more (assoc opts :intent (or b "")))

          (#{"-k" "--keyword"} a)
          (recur more (assoc opts :keyword b))

          (#{"-n" "--limit"} a)
          (recur more (assoc opts :limit (when b (Integer/parseInt b))))

          (str/blank? (:intent opts))
          (recur (rest xs) (assoc opts :intent a))

          :else
          (recur (rest xs) (update opts :intent #(str % " " a))))))))

(defn- parse-check-opts [args]
  (loop [xs args
         opts {:cmd-parts [] :json? false :hook-stdin? false :strict-preview? true :block-pkg-installs? true :help? false}]
    (if (empty? xs)
      (assoc opts :command (str/join " " (:cmd-parts opts)))
      (let [a (first xs)]
        (cond
          (#{"-h" "--help"} a) (recur (rest xs) (assoc opts :help? true))
          (= "--json" a) (recur (rest xs) (assoc opts :json? true))
          (= "--hook-stdin" a) (recur (rest xs) (assoc opts :hook-stdin? true))
          (= "--no-strict-preview" a) (recur (rest xs) (assoc opts :strict-preview? false))
          (= "--allow-pkg-installs" a) (recur (rest xs) (assoc opts :block-pkg-installs? false))
          :else (recur (rest xs) (update opts :cmd-parts conj a)))))))

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
            mandy-root (or (System/getenv "MANDY_ROOT") (System/getProperty "user.dir"))
            tui-cmd (if (.exists mandy-ui-bin)
                      [(.getAbsolutePath mandy-ui-bin)]
                      ["bun" "run" (str mandy-root "/src/index.ts")])
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
  (cond
    (= "mcp" (first args))
    (do (mcp/serve-stdio!)
        (System/exit 0))

    (= "discover" (first args))
    (let [{:keys [intent keyword limit json? help?]} (parse-discover-opts (rest args))]
      (when (or help? (and (str/blank? intent) (str/blank? keyword)))
        (display-help)
        (System/exit 0))
      (let [res (ubuntu/discover {:intent (if (str/blank? intent) keyword intent)
                                  :keyword keyword
                                  :limit limit})]
        (if json?
          (println (json/generate-string res))
          (print (ubuntu/format-discovery-yaml res)))
        (flush)
        (System/exit 0)))

    (= "check" (first args))
    (let [{:keys [command json? hook-stdin? strict-preview? block-pkg-installs? help?]} (parse-check-opts (rest args))]
      (when help?
        (display-help)
        (System/exit 0))
      (let [stdin-payload (when hook-stdin?
                            (try
                              (let [raw (slurp *in*)]
                                (when-not (str/blank? raw)
                                  (json/parse-string raw true)))
                              (catch Exception _ nil)))
            tool-name (:tool_name stdin-payload)
            hook-cmd (or (get-in stdin-payload [:tool_input :command])
                         (:command stdin-payload))
            target-cmd (if hook-stdin? hook-cmd command)]
        (when (and hook-stdin? tool-name
                   (not (contains? #{"Bash" "bash" "run_shell_command" "shell"} (str tool-name))))
          (System/exit 0))
        (when (str/blank? target-cmd)
          (System/exit 0))
        (let [res (check/check-command target-cmd {:strict-preview? strict-preview?
                                                   :block-pkg-installs? block-pkg-installs?})
              blocked? (= "block" (:status res))]
          (cond
            json? (do (println (json/generate-string res)) (flush))
            (and hook-stdin? blocked?) (binding [*out* *err*]
                                         (println (check/format-check-text res))
                                         (flush))
            (not hook-stdin?) (do (println (check/format-check-text res)) (flush)))
          (System/exit (if blocked? 2 0))))))

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
            _ (when-not keyword (do (println "Error: No keyword provided for deep search.") (System/exit 1)))]
        (if (and is-deep-cli (not (System/getenv "MANDY_LEGACY_SEARCH")))
          (let [res (ubuntu/discover {:intent keyword :keyword keyword :limit 5})]
            (if is-json
              (println (json/generate-string res))
              (print (ubuntu/format-discovery-yaml res)))
            (flush))
          (let [_ (binding [*out* *err*] (print (str "Searching for \"" keyword "\"... ")) (flush))
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
                  (println (yaml/generate-string {:RESULTS unique-results})))))))
      
      (if-not command
        (do (println "Error: No command specified.")
            (System/exit 1))
        
        (if (or context-string after-n)
          ;; Context-based discovery mode
          (let [man-cmd (str "man " command " | col -b")
                man-raw (:out (sh "bash" "-c" man-cmd))
                lines (str/split-lines man-raw)
                indexed-lines (map-indexed vector lines)
                structured-data (parse-man indexed-lines)
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
