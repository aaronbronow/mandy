(ns mandy.strategy
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str])
  (:import [java.io File]))

(defonce ^:private strategy-cache (atom nil))

(def ^:private default-strategy
  {:man_sections [1 6 8]
   :max_candidates 5
   :exact_binary_limit 1
   :default_limit 2
   :max_flags_per_tool 5
   :max_flag_desc_chars 75
   :lean_max_flags_per_tool 3
   :lean_max_flag_desc_chars 48
   :lean_unmatched_flag_cap 1
   :l1_cache_ttl_ms 30000
   :preflight_specialized_tools ["b2sum" "tree" "pdftotext" "pdfunite" "ys" "busybox"]
   :min_candidate_score 40
   :help_timeout_ms 1500
   :man_deep_timeout_ms 2500
   :cache_rel_path ".cache/mandy/ubuntu-discovery-cache.json"
   :path_priority ["/usr/bin" "/bin" "/usr/sbin" "/sbin" "/usr/local/bin"]
   :stopwords ["the" "a" "an" "to" "for" "of" "in" "on" "with" "using" "file" "files"
               "command" "tool" "cli" "ubuntu" "linux" "how" "and" "or" "from" "into"
               "all" "get" "list" "show" "run" "make" "use" "check" "find" "create"
               "convert" "extract"]
   :intent_synonyms {}
   :blocked_package_managers ["apt" "apt-get" "dpkg" "snap" "pip" "pip3"]
   :command_safety {}
   :weights {:exact_binary_match 120
             :binary_prefix_match 60
             :package_name_match 50
             :apropos_match 40
             :dpkg_description_match 30
             :man_deep_match 25
             :intent_token_in_summary 15
             :intent_token_in_flags 12
             :has_man_page 10
             :dpkg_managed 8
             :standard_usr_bin 5}})

(defn- find-strategy-file
  "Find plugins/ubuntu_discovery.ys across MANDY_ROOT, cwd, or jar parent dir."
  []
  (let [cp (System/getProperty "java.class.path")
        first-cp (when-not (str/blank? cp)
                   (first (str/split cp (re-pattern (System/getProperty "path.separator")))))
        ^File jar-parent (when-not (str/blank? first-cp)
                           (.getParentFile (.getAbsoluteFile (io/file first-cp))))
        candidates (cond-> []
                     (System/getenv "MANDY_ROOT")
                     (conj (io/file (System/getenv "MANDY_ROOT") "plugins" "ubuntu_discovery.ys"))
                     true
                     (conj (io/file "plugins" "ubuntu_discovery.ys"))
                     jar-parent
                     (conj (io/file jar-parent "plugins" "ubuntu_discovery.ys")
                           (when-let [^File gp (.getParentFile jar-parent)]
                             (io/file gp "plugins" "ubuntu_discovery.ys"))))]
    (first (filter #(and % (.exists ^File %) (.isFile ^File %)) candidates))))

(defn- strip-ys-header [content]
  (let [lines (str/split-lines content)]
    (if (and (seq lines) (str/starts-with? (str/trim (first lines)) "!yamlscript"))
      (str/join "\n" (rest lines))
      content)))

(defn- eval-ys-text
  "Evaluate YAMLScript text. Fast-path !yamlscript/v0/data via in-process clj-yaml, reserving `ys -J` for code mode."
  [^File file ^String content]
  (let [trimmed (str/trim (or content ""))
        data-mode? (str/starts-with? trimmed "!yamlscript/v0/data")]
    (if data-mode?
      (try
        (yaml/parse-string (strip-ys-header content) true)
        (catch Exception _
          (let [res (when file
                      (try
                        (sh "ys" "-J" (.getAbsolutePath file))
                        (catch Exception _ nil)))]
            (when (and res (zero? (:exit res)) (not (str/blank? (:out res))))
              (json/parse-string (:out res) true)))))
      (let [res (when file
                  (try
                    (sh "ys" "-J" (.getAbsolutePath file))
                    (catch Exception _ nil)))]
        (if (and res (zero? (:exit res)) (not (str/blank? (:out res))))
          (json/parse-string (:out res) true)
          (yaml/parse-string (strip-ys-header content) true))))))

(defn load-ubuntu-strategy
  "Load Ubuntu discovery strategy from plugins/ubuntu_discovery.ys, memoized by mtime."
  []
  (let [^File file (find-strategy-file)]
    (if file
      (let [mtime (.lastModified file)
            cached @strategy-cache]
        (if (and cached (= (:mtime cached) mtime) (= (:path cached) (.getAbsolutePath file)))
          (:data cached)
          (let [raw (slurp file)
                parsed (merge default-strategy (eval-ys-text file raw))]
            (reset! strategy-cache {:mtime mtime
                                    :path (.getAbsolutePath file)
                                    :data parsed})
            parsed)))
      (if-let [res (io/resource "ubuntu_discovery.ys")]
        (let [cached @strategy-cache]
          (if (and cached (= (:path cached) :resource))
            (:data cached)
            (let [raw (slurp res)
                  parsed (merge default-strategy (eval-ys-text nil raw))]
              (reset! strategy-cache {:mtime 0 :path :resource :data parsed})
              parsed)))
        default-strategy))))
