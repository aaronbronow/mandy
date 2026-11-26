(ns mandy.ubuntu
  (:require [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mandy.strategy :as strategy])
  (:import [java.io ByteArrayOutputStream File InputStream]
           [java.nio.file Files LinkOption Paths StandardCopyOption]
           [java.util.concurrent TimeUnit]))

;; --- Shared Text & Man Parsing Helpers ---

(defn heading? [line]
  (re-matches #"[A-Z][A-Z0-9()\s]*[A-Z0-9)]" (str/trim (or line ""))))

(defn sanitize [line]
  (-> (or line "")
      (str/replace "\t" " ")
      (str/replace "\u2010" "-")
      (str/replace #"\s+" " ")
      str/trim))

(defn strip-troff [line]
  (-> (or line "")
      (str/replace #"^\.[A-Z]{1,10}(\s+|$)" "")
      (str/replace #"^\.[a-z]{1,2}(\s+|$)" "")
      (str/replace #"^\.\s*$" "")
      (str/replace #"\.IX\s+.*" "")
      (str/replace #"\.[IB][IRP]?\s+" "")
      (str/replace #"\.(It|Ic|Ar|Fl)\s+" "")
      (str/replace #"\\f[BIRP]" "")
      (str/replace #"\\s[+-]?\d+" "")
      (str/replace #"\\\*(\([a-zA-Z0-9]{2}|\[[a-zA-Z0-9]+\]|.)" "")
      (str/replace #"\\n(\([a-zA-Z0-9]{2}|\[[a-zA-Z0-9]+\]|.)" "")
      (str/replace #"\\&" "")
      (str/replace #"\\\(.." "")
      (str/replace #"\\." "")))

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

;; --- Safe Subprocess Runner ---

(defn- read-bounded ^String [^InputStream is max-bytes]
  (let [buf (byte-array 4096)
        baos (ByteArrayOutputStream.)]
    (loop [total 0]
      (let [n (.read is buf)]
        (if (neg? n)
          (.toString baos "UTF-8")
          (let [to-write (min n (max 0 (- max-bytes total)))]
            (when (pos? to-write)
              (.write baos buf 0 to-write))
            (recur (+ total n))))))))

(defn run-proc
  "Execute cmd-vec safely with non-interactive env, /dev/null stdin, bounded output, and timeout."
  ([cmd-vec] (run-proc cmd-vec {}))
  ([cmd-vec {:keys [timeout-ms max-bytes extra-env]
             :or {timeout-ms 1500 max-bytes 131072}}]
   (try
     (let [pb (ProcessBuilder. ^java.util.List (mapv str cmd-vec))
           env (.environment pb)]
       (.redirectInput pb (java.lang.ProcessBuilder$Redirect/from (io/file "/dev/null")))
       (.put env "PAGER" "cat")
       (.put env "MANPAGER" "cat")
       (.put env "TERM" "dumb")
       (.put env "NO_COLOR" "1")
       (.put env "COLUMNS" "120")
       (.put env "MANWIDTH" "120")
       (.put env "DEBIAN_FRONTEND" "noninteractive")
       (.put env "LC_ALL" "C.UTF-8")
       (.remove env "DISPLAY")
       (doseq [[k v] extra-env]
         (.put env (str k) (str v)))
       (let [proc (.start pb)
             out-f (future (read-bounded (.getInputStream proc) max-bytes))
             err-f (future (read-bounded (.getErrorStream proc) max-bytes))
             finished (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)]
         (when-not finished
           (.destroyForcibly proc)
           (.waitFor proc 200 TimeUnit/MILLISECONDS))
         {:exit (if finished (.exitValue proc) -1)
          :out (deref out-f 500 "")
          :err (deref err-f 500 "")
          :timed-out? (not finished)}))
     (catch Exception e
       {:exit -1 :out "" :err (.getMessage e) :timed-out? false}))))

;; --- Local PATH Executable Verification ---

(defn- executable-file? [^File f]
  (when f
    (try
      (let [p (.toPath f)]
        (and (Files/isRegularFile p (make-array LinkOption 0))
             (Files/isExecutable p)))
      (catch Exception _ false))))

(defn scan-path-executables
  "Scan PATH directories and return {cmd-name {:cmd :path}} for executable regular files."
  []
  (let [path-env (or (System/getenv "PATH") "/usr/bin:/bin")]
    (reduce (fn [acc dir-str]
              (let [dir (io/file dir-str)]
                (if (and (.exists dir) (.isDirectory dir))
                  (reduce (fn [m ^File f]
                            (let [name (.getName f)]
                              (if (and (not (contains? m name))
                                       (executable-file? f))
                                (assoc m name {:cmd name :path (.getAbsolutePath f)})
                                m)))
                          acc
                          (or (.listFiles dir) []))
                  acc)))
            {}
            (remove str/blank? (str/split path-env #":")))))

(defn resolve-exec-info
  "Resolve realpath and mtime for a verified PATH executable map."
  [{:keys [cmd path] :as entry}]
  (when entry
    (try
      (let [p (Paths/get path (into-array String []))
            rp (.toString (.toRealPath p (into-array LinkOption [])))
            mtime (.lastModified (io/file rp))]
        (assoc entry :realpath rp :mtime mtime))
      (catch Exception _
        (assoc entry :realpath path :mtime (.lastModified (io/file path)))))))

;; --- Offline dpkg Metadata & Provenance ---

(defonce ^:private dpkg-status-cache (atom nil))

(defn- parse-dpkg-status-text [text]
  (let [stanzas (str/split text #"\n\n+")]
    (reduce (fn [acc stanza]
              (let [lines (str/split-lines stanza)
                    parsed (reduce (fn [{:keys [fields last-key]} line]
                                     (cond
                                       (str/starts-with? line " ")
                                       (if (= last-key :Description)
                                         {:fields (update fields :full-desc conj (str/trim line))
                                          :last-key :Description}
                                         {:fields fields :last-key last-key})

                                       :else
                                       (let [idx (.indexOf line ":")]
                                         (if (pos? idx)
                                           (let [k (keyword (subs line 0 idx))
                                                 v (str/trim (subs line (inc idx)))]
                                             {:fields (if (= k :Description)
                                                        (assoc fields :summary v :full-desc [v])
                                                        (assoc fields k v))
                                              :last-key k})
                                           {:fields fields :last-key nil}))))
                                   {:fields {} :last-key nil}
                                   lines)
                    f (:fields parsed)
                    pkg (:Package f)
                    status (or (:Status f) "")]
                (if (and pkg (str/includes? status "install ok installed"))
                  (let [desc-str (str/join " " (remove #(= % ".") (:full-desc f)))]
                    (assoc acc pkg {:package pkg
                                    :version (:Version f)
                                    :summary (or (:summary f) "")
                                    :search-text (str/lower-case (str pkg " " desc-str))}))
                  acc)))
            {}
            stanzas)))

(defn load-dpkg-status
  "Load installed packages from /var/lib/dpkg/status, memoized by file mtime."
  []
  (let [f (io/file "/var/lib/dpkg/status")]
    (if-not (.exists f)
      {}
      (let [mtime (.lastModified f)
            cached @dpkg-status-cache]
        (if (and cached (= (:mtime cached) mtime))
          (:data cached)
          (let [data (parse-dpkg-status-text (slurp f))]
            (reset! dpkg-status-cache {:mtime mtime :data data})
            data))))))

(defn- find-dpkg-list-file [^String pkg]
  (let [direct (io/file (str "/var/lib/dpkg/info/" pkg ".list"))]
    (if (.exists direct)
      direct
      (let [info-dir (io/file "/var/lib/dpkg/info")
            prefix (str pkg ":")]
        (when (.exists info-dir)
          (first (filter #(let [n (.getName ^File %)]
                            (and (str/starts-with? n prefix)
                                 (str/ends-with? n ".list")))
                         (or (.listFiles info-dir) []))))))))

(defn package-binaries
  "Extract verified PATH executable names owned by a dpkg package from /var/lib/dpkg/info/*.list."
  [pkg path-execs]
  (let [list-file (find-dpkg-list-file pkg)
        lines (cond
                list-file (str/split-lines (slurp list-file))
                :else (let [res (run-proc ["dpkg-query" "-L" pkg] {:timeout-ms 1000})]
                        (if (zero? (:exit res)) (str/split-lines (:out res)) [])))]
    (->> lines
         (keep (fn [line]
                 (when-let [[_ bin-name] (re-matches #"^/(?:usr/)?(?:s?bin|lib/cargo/bin/coreutils)/([^/]+)$" line)]
                   (when (contains? path-execs bin-name)
                     bin-name))))
         distinct
         vec)))

(defn resolve-dpkg-provenance
  "Batch-resolve owning dpkg package metadata for surviving verified candidate paths."
  [exec-infos dpkg-map]
  (let [paths-to-query (->> exec-infos
                            (mapcat (fn [{:keys [path realpath]}]
                                      (distinct (remove nil? [realpath path
                                                              (when (str/starts-with? (or path "") "/usr/")
                                                                (subs path 4))]))))
                            distinct
                            vec)]
    (if (empty? paths-to-query)
      {}
      (let [res (run-proc (into ["dpkg-query" "-S"] paths-to-query) {:timeout-ms 1500})
            path->pkg (reduce (fn [acc line]
                                (if-let [[_ pkgs-part p] (re-matches #"^([^:]+):\s+(/.*)$" (str/trim line))]
                                  (let [pkg (first (str/split pkgs-part #",\s*"))]
                                    (assoc acc p pkg))
                                  acc))
                              {}
                              (str/split-lines (or (:out res) "")))]
        (reduce (fn [acc {:keys [cmd path realpath]}]
                  (let [pkg (or (get path->pkg realpath)
                                (get path->pkg path)
                                (when (str/starts-with? (or path "") "/usr/")
                                  (get path->pkg (subs path 4))))
                        meta-info (when pkg (get dpkg-map pkg))]
                    (assoc acc cmd (if pkg
                                     {:package pkg
                                      :manager "dpkg"
                                      :version (:version meta-info)
                                      :package_summary (:summary meta-info)}
                                     {:package nil
                                      :manager "path"
                                      :version nil
                                      :package_summary nil}))))
                {}
                exec-infos)))))

;; --- Multiline State-Carrying Flag Extractor (man & --help) ---

(def ^:private flag-inline-re
  #"^\s{1,12}(-[a-zA-Z0-9?\[][^\s]*(?:(?:,\s*|\s*/\s*)(?:-[a-zA-Z0-9?\[][^\s]*|\S+))*(?:\s+(?:[A-Z0-9_<>\[][A-Z0-9_<>.\[\]|-]*|[a-z]+))?)(?:\s{2,}|\t+)(.+?)\s*$")

(def ^:private flag-header-re
  #"^\s{1,12}((?:--[a-zA-Z0-9][a-zA-Z0-9_-]*|-[a-zA-Z0-9?\[][^\s]*)(?:(?:,\s*|\s*/\s*)(?:--[a-zA-Z0-9][a-zA-Z0-9_-]*|-[a-zA-Z0-9?][^\s]*|[A-Z0-9_<>=.-]+))*(?:=\S+|\s+[a-zA-Z0-9_<>\[][^\s()]*)?(?:\s*\([^)]{1,35}\))?:?)\s*$")

(def ^:private skip-man-sections
  #{"PREFACE" "SYNOPSIS" "EXAMPLES" "EXIT STATUS" "ENVIRONMENT" "FILES"
    "SEE ALSO" "AUTHOR" "AUTHORS" "REPORTING BUGS" "COPYRIGHT" "BUGS"
    "HISTORY" "STANDARDS" "COMPATIBILITY" "NOTES"})

(defn- clean-spec [s]
  (-> s
      (str/replace #":\s*$" "")
      (str/replace #"\s+" " ")
      str/trim))

(defn- valid-flag-spec? [spec]
  (let [s (clean-spec spec)]
    (and (str/starts-with? s "-")
         (not (str/starts-with? s "- "))
         (<= (count s) 72)
         (not (re-find #"^-\d+$" s)))))

(defn- parse-flag-line [raw-line]
  (let [line (str/replace (or raw-line "") "\t" "        ")
        leading-spaces (count (re-find #"^\s*" line))]
    (when (and (<= 1 leading-spaces 12) (not (str/blank? line)))
      (or
        (when-let [[_ spec desc] (re-matches flag-inline-re line)]
          (when (valid-flag-spec? spec)
            {:spec (clean-spec spec) :desc (sanitize desc) :indent leading-spaces :inline? true}))
        (when-let [[_ spec] (re-matches flag-header-re line)]
          (when (valid-flag-spec? spec)
            {:spec (clean-spec spec) :desc "" :indent leading-spaces :inline? false}))))))

(defn- flush-current [acc]
  (if-let [cur (:current acc)]
    (-> acc
        (update :entries conj {:spec (:spec cur) :desc (sanitize (:desc cur))})
        (assoc :current nil :saw-blank? false))
    acc))

(defn extract-flag-entries
  "Multiline state-carrying reduce over man or --help lines to extract {:spec :desc} pairs."
  [lines]
  (let [final-state
        (reduce
          (fn [acc raw-line]
            (let [line (-> raw-line (str/replace "\t" "        ") (str/replace "\u2010" "-"))
                  trimmed (str/trim line)
                  leading (count (re-find #"^\s*" line))]
              (cond
                (str/blank? trimmed)
                (if-let [cur (:current acc)]
                  (if (and (str/blank? (:desc cur)) (not (:saw-blank? acc)))
                    (assoc acc :saw-blank? true)
                    (flush-current acc))
                  acc)

                (heading? line)
                (flush-current acc)

                :else
                (if-let [parsed (parse-flag-line line)]
                  (let [acc' (flush-current acc)]
                    (assoc acc'
                           :current {:spec (:spec parsed)
                                     :desc (:desc parsed)
                                     :indent (:indent parsed)}
                           :saw-blank? false))
                  (if-let [cur (:current acc)]
                    (if (and (> leading (:indent cur))
                             (< (count (:desc cur)) 260))
                      (let [new-desc (if (str/blank? (:desc cur))
                                       trimmed
                                       (str (:desc cur) " " trimmed))]
                        (-> acc
                            (assoc-in [:current :desc] new-desc)
                            (assoc :saw-blank? false)))
                      (flush-current acc))
                    acc)))))
          {:entries [] :current nil :saw-blank? false}
          lines)
        entries (:entries (flush-current final-state))]
    (->> entries
         (filter #(not (str/blank? (:desc %))))
         (reduce (fn [{:keys [seen out]} item]
                   (if (contains? seen (:spec item))
                     {:seen seen :out out}
                     {:seen (conj seen (:spec item)) :out (conj out item)}))
                 {:seen #{} :out []})
         :out)))

;; --- Cache & Interface Extraction (man + --help fallback) ---

(defn- cache-file [strategy]
  (let [home (System/getProperty "user.home")
        rel (or (:cache_rel_path strategy) ".cache/mandy/ubuntu-discovery-cache.json")]
    (io/file home rel)))

(defn load-discovery-cache [strategy]
  (let [f (cache-file strategy)]
    (if (.exists f)
      (try
        (json/parse-string (slurp f) false)
        (catch Exception _ {}))
      {})))

(defn save-discovery-cache! [strategy cache-map]
  (let [^File f (cache-file strategy)
        ^File parent (.getParentFile f)]
    (try
      (.mkdirs parent)
      (let [tmp (File/createTempFile "ubuntu-cache-" ".json" parent)]
        (spit tmp (json/generate-string cache-map))
        (Files/move (.toPath tmp) (.toPath f)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING
                                                    StandardCopyOption/ATOMIC_MOVE])))
      (catch Exception _ nil))))

(defn- extract-man-summary [structured-data cmd pkg-summary]
  (let [name-sec (some (fn [sec] (get sec "NAME")) structured-data)
        joined (->> name-sec (map :text) (map sanitize) (remove str/blank?) (str/join " "))
        after-dash (when-not (str/blank? joined)
                     (second (re-find #"^[^\s]+\s+(?:-\s+|—\s+|–\s+)(.+)$" joined)))]
    (or (when-not (str/blank? after-dash) after-dash)
        (when-not (str/blank? joined) joined)
        pkg-summary
        cmd)))

(defn- extract-help-summary [lines cmd pkg-summary]
  (let [candidates (->> lines
                        (take 10)
                        (map sanitize)
                        (remove str/blank?)
                        (remove #(re-find #"(?i)^(usage:|options:|commands:)" %))
                        (remove #(str/starts-with? % "-")))]
    (or (when-not (str/blank? pkg-summary) pkg-summary)
        (first candidates)
        cmd)))

(defn- man-section-from-path [man-path]
  (when-let [[_ sec] (re-find #"\.([0-9][a-zA-Z]*)(?:\.gz|\.bz2|\.xz)?$" (str/trim (or man-path "")))]
    sec))

(defn- relevant-man-lines [structured-data]
  (let [preferred (->> structured-data
                       (filter (fn [sec]
                                 (let [h (first (keys sec))]
                                   (and (not (contains? skip-man-sections h))
                                        (re-find #"(?i)(OPTIONS|FLAGS|COMMANDS)" h)))))
                       (mapcat #(map :text (val (first %))))
                       vec)]
    (if (seq preferred)
      preferred
      (->> structured-data
           (remove (fn [sec] (contains? skip-man-sections (first (keys sec)))))
           (mapcat #(map :text (val (first %))))
           vec))))

(defn- probe-help-flags [exec-path timeout-ms]
  (loop [flags ["--help" "-help" "-h"]]
    (when-let [flag (first flags)]
      (let [res (run-proc [exec-path flag] {:timeout-ms timeout-ms})
            combined (str (:out res) "\n" (:err res))
            lines (str/split-lines combined)
            entries (extract-flag-entries lines)
            usage-entries (if (seq entries)
                            entries
                            (->> lines
                                 (keep (fn [l]
                                         (when-let [[_ flag-tok] (re-find #"^\s*(?:Usage:|or:)\s+\S+\s+(--[a-zA-Z0-9_.-]+(?:\[[^\]]+\])?(?:\s+\S+)*)" l)]
                                           {:spec (str/trim flag-tok) :desc (sanitize l)})))
                                 vec))]
        (if (or (seq usage-entries) (and (zero? (:exit res)) (> (count (str/trim combined)) 40)))
          {:help-flag flag :lines lines :entries usage-entries}
          (recur (rest flags)))))))

(defn- extract-known-long-flags [text]
  (let [raw-txt (or text "")
        plain (re-seq #"--[a-zA-Z0-9][a-zA-Z0-9_-]*" raw-txt)
        bracketed (mapcat (fn [[_ stem]]
                            [(str "--" stem) (str "--no-" stem)])
                          (re-seq #"--\[no-\]([a-zA-Z0-9][a-zA-Z0-9_-]*)" raw-txt))]
    (->> (concat plain bracketed)
         distinct
         sort
         vec)))

(defn- detect-dry-run-flag [flags]
  (some (fn [{:keys [spec]}]
          (when (re-find #"(?i)(--dry-run|--simulate|--just-print)" (or spec ""))
            spec))
        flags))

(defn extract-interface
  "Extract summary, structured flags, and safety metadata for a verified binary, backed by (realpath, mtime) cache."
  [exec-info pkg-summary strategy cache-atom]
  (let [{:keys [cmd realpath mtime]} exec-info
        cached (get @cache-atom realpath)]
    (if (and cached
             (= (get cached "schema_version") 2)
             (= (get cached "mtime") mtime))
      {:interface_source (get cached "interface_source")
       :summary (get cached "summary")
       :flags (mapv (fn [f] {:spec (get f "spec") :desc (get f "desc")}) (get cached "flags"))
       :known_long_flags (vec (get cached "known_long_flags"))
       :dry_run_flag (get cached "dry_run_flag")
       :cache_hit true}
      (let [help-ms (or (:help_timeout_ms strategy) 1500)
            man-w-sec (run-proc ["man" "-w" "-S" (str/join ":" (:man_sections strategy)) cmd] {:timeout-ms 1000})
            man-w (if (zero? (:exit man-w-sec))
                    man-w-sec
                    (run-proc ["man" "-w" cmd] {:timeout-ms 1000}))
            man-path (when (zero? (:exit man-w)) (first (str/split-lines (:out man-w))))
            man-sec (man-section-from-path man-path)
            man-rendered (when man-path
                           (let [res (run-proc ["bash" "-c" (str "MANWIDTH=120 man " (or man-sec "") " " cmd " 2>/dev/null | col -b")]
                                               {:timeout-ms 2500})]
                             (when (and (zero? (:exit res)) (not (str/blank? (:out res))))
                               (:out res))))
            structured (when man-rendered
                         (parse-man (map-indexed vector (str/split-lines man-rendered))))
            man-flags (when structured (extract-flag-entries (relevant-man-lines structured)))
            help-res (when (or (nil? man-rendered) (empty? man-flags))
                       (probe-help-flags realpath help-ms))
            interface-source (cond
                               (and man-sec (seq man-flags)) (str "man(" man-sec ")")
                               (and man-sec (seq (:entries help-res))) (str "man(" man-sec ")+" (:help-flag help-res))
                               man-sec (str "man(" man-sec ")")
                               help-res (:help-flag help-res)
                               :else "binary")
            summary (cond
                      structured (extract-man-summary structured cmd pkg-summary)
                      help-res (extract-help-summary (:lines help-res) cmd pkg-summary)
                      (not (str/blank? pkg-summary)) pkg-summary
                      :else cmd)
            flags (cond
                    (seq man-flags) man-flags
                    (seq (:entries help-res)) (:entries help-res)
                    :else [])
            full-text (str (or man-rendered "") "\n" (str/join "\n" (or (:lines help-res) [])))
            known-long-flags (extract-known-long-flags full-text)
            dry-run-flag (detect-dry-run-flag flags)
            record (cond-> {"schema_version" 2
                            "mtime" mtime
                            "interface_source" interface-source
                            "summary" summary
                            "flags" (mapv (fn [{:keys [spec desc]}] {"spec" spec "desc" desc}) flags)
                            "known_long_flags" known-long-flags}
                     dry-run-flag (assoc "dry_run_flag" dry-run-flag))]
        (swap! cache-atom assoc realpath record)
        {:interface_source interface-source
         :summary summary
         :flags flags
         :known_long_flags known-long-flags
         :dry_run_flag dry-run-flag
         :cache_hit false}))))

;; --- Candidate Gathering, Ranking & Flag Selection ---

(defn- tokenize-query [intent keyword-str strategy]
  (let [stops (set (map str/lower-case (:stopwords strategy)))
        syns (:intent_synonyms strategy)
        raw-text (str/lower-case (str (or keyword-str "") " " (or intent "")))
        primary (->> (re-seq #"[a-z0-9][a-z0-9_+.-]*" raw-text)
                     (remove #(or (contains? stops %) (< (count %) 2)))
                     distinct
                     vec)
        expanded (->> primary
                      (mapcat #(get syns (keyword %) []))
                      (map str/lower-case)
                      (remove (set primary))
                      distinct
                      vec)]
    {:primary primary
     :expanded expanded
     :all (vec (distinct (concat primary expanded)))
     :keyword-token (when-not (str/blank? keyword-str)
                      (let [k (str/trim (str/lower-case keyword-str))]
                        (when (re-matches #"[a-z0-9][a-z0-9_+.-]*" k) k)))}))

(defn- gather-apropos [terms path-execs]
  (reduce (fn [acc term]
            (let [res (run-proc ["apropos" "-s" "1,6,8" term] {:timeout-ms 700})]
              (if (zero? (:exit res))
                (reduce (fn [m line]
                          (if-let [[_ cmd _ desc] (re-matches #"^([^\s(]+)\s+\(([^)]+)\)\s+-\s+(.*)$" line)]
                            (if (contains? path-execs cmd)
                              (update m cmd #(or % {:apropos-desc desc}))
                              m)
                            m))
                        acc
                        (str/split-lines (:out res)))
                acc)))
          {}
          (take 3 terms)))

(defn- word-match? [text term]
  (when (and text term (not (str/blank? term)))
    (boolean (re-find (re-pattern (str "(?i)\\b" (java.util.regex.Pattern/quote term) "\\b")) text))))

(defn- gather-dpkg-candidates [terms dpkg-map path-execs]
  (let [scored (->> dpkg-map
                    (keep (fn [[pkg info]]
                            (let [txt (:search-text info)
                                  pkg-l (str/lower-case pkg)
                                  hits (reduce (fn [s t]
                                                 (+ s
                                                    (if (= pkg-l t) 8 0)
                                                    (if (word-match? pkg-l t) 4 0)
                                                    (if (word-match? txt t) 2 0)))
                                               0
                                               terms)]
                              (when (pos? hits)
                                {:pkg pkg :score hits}))))
                    (sort-by (juxt (comp - :score) :pkg))
                    (take 10))]
    (reduce (fn [acc {:keys [pkg score]}]
              (reduce (fn [m bin-name]
                        (update m bin-name (fnil max 0) score))
                      acc
                      (package-binaries pkg path-execs)))
            {}
            scored)))

(defn- gather-man-deep [term strategy path-execs]
  (when-not (str/blank? term)
    (let [secs (str/join ":" (:man_sections strategy))
          res (run-proc ["man" "-S" secs "-wK" term]
                        {:timeout-ms (or (:man_deep_timeout_ms strategy) 2500)})]
      (if (zero? (:exit res))
        (->> (str/split-lines (:out res))
             (keep (fn [path]
                     (let [fname (last (str/split (str/trim path) #"/"))
                           cmd (first (str/split fname #"\."))]
                       (when (contains? path-execs cmd) cmd))))
             distinct
             (take 12)
             set)
        #{}))))

(defn- gather-candidates [{:keys [primary expanded all keyword-token]} strategy dpkg-map path-execs]
  (let [direct-hits (reduce (fn [acc [cmd _]]
                              (let [c-l (str/lower-case cmd)]
                                (if (or (= c-l keyword-token)
                                        (some #(= c-l %) all)
                                        (some #(and (>= (count %) 3) (str/starts-with? c-l %)) primary))
                                  (conj acc cmd)
                                  acc)))
                            #{}
                            path-execs)
        apropos-map (gather-apropos (distinct (remove nil? (concat (when keyword-token [keyword-token])
                                                                   (take 2 primary)
                                                                   (take 2 expanded))))
                                    path-execs)
        dpkg-hits (gather-dpkg-candidates (take 6 all) dpkg-map path-execs)
        combined-pre (distinct (concat direct-hits (keys apropos-map) (keys dpkg-hits)))
        deep-set (if (< (count combined-pre) (or (:default_limit strategy) 3))
                   (gather-man-deep (or keyword-token (first primary)) strategy path-execs)
                   #{})
        all-cmds (->> (concat direct-hits (keys apropos-map) (keys dpkg-hits) deep-set)
                      (filter #(contains? path-execs %))
                      distinct
                      vec)]
    {:cmds all-cmds
     :apropos apropos-map
     :dpkg-hits dpkg-hits
     :deep-set deep-set}))

(defn- pre-score-candidate [cmd {:keys [primary expanded keyword-token]} {:keys [apropos dpkg-hits deep-set]} path-execs weights]
  (let [c-l (str/lower-case cmd)
        path (get-in path-execs [cmd :path] "")
        ap-desc (str/lower-case (or (get-in apropos [cmd :apropos-desc]) ""))
        dpkg-score (get dpkg-hits cmd 0)]
    (+ (if (= c-l keyword-token) (or (:exact_binary_match weights) 120) 0)
       (if (some #(= c-l %) primary) (or (:exact_binary_match weights) 120) 0)
       (if (some #(= c-l %) expanded) (or (:binary_prefix_match weights) 60) 0)
       (if (some #(and (>= (count %) 3) (str/starts-with? c-l %)) primary) (or (:binary_prefix_match weights) 60) 0)
       (if (contains? apropos cmd) (or (:apropos_match weights) 40) 0)
       (* (min 5 (count (filter #(str/includes? ap-desc %) primary))) (or (:intent_token_in_summary weights) 15))
       (* (min 4 dpkg-score) (or (:dpkg_description_match weights) 30))
       (if (contains? deep-set cmd) (or (:man_deep_match weights) 25) 0)
       (if (str/starts-with? path "/usr/bin/") (or (:standard_usr_bin weights) 5) 0))))

(defn- select-relevant-flags [flags primary-tokens max-flags max-desc-chars]
  (let [wants-meta? (some #{"help" "version" "license" "formats"} primary-tokens)
        scored (map-indexed
                 (fn [idx {:keys [spec desc]}]
                   (let [spec-l (str/lower-case spec)
                         desc-l (str/lower-case desc)
                         spec-hits (count (filter #(word-match? spec-l %) primary-tokens))
                         desc-hits (count (filter #(word-match? desc-l %) primary-tokens))
                         has-short? (boolean (re-find #"^-[a-zA-Z0-9](\s|,|$)" spec))
                         concise? (<= (count spec) 26)
                         meta-pen (if (and (not wants-meta?)
                                           (re-find #"^(-h|-L|-V|--help|--version|-version|-buildconf|-formats|-demuxers|-muxers|-devices|-codecs|-decoders|-encoders|-bsfs|-h,)" spec))
                                    -3
                                    0)
                         score (+ (* 4 spec-hits)
                                  (* 2 desc-hits)
                                  (if has-short? 1 0)
                                  (if concise? 1 0)
                                  meta-pen)]
                     {:idx idx :score score :spec spec :desc desc}))
                 flags)
        chosen (->> scored
                    (sort-by (juxt (comp - :score) :idx))
                    (take max-flags)
                    (sort-by :idx))]
    (mapv (fn [{:keys [spec desc]}]
            (let [d (if (> (count desc) max-desc-chars)
                      (str (subs desc 0 (- max-desc-chars 3)) "...")
                      desc)]
              (str spec ": " d)))
          chosen)))

(defn format-discovery-yaml
  "Render compact Agent-First YAML output."
  [result-map]
  (str "!yamlscript/v0/data\n"
       (yaml/generate-string result-map :dumper-options {:flow-style :block})))

(defn discover
  "Run Ubuntu live tool discovery and return structured map with verified local executables."
  [{:keys [intent keyword limit] :or {intent "" keyword nil limit nil}}]
  (let [strat (strategy/load-ubuntu-strategy)
        weights (:weights strat)
        max-cand (or (:max_candidates strat) 5)
        max-flags (or (:max_flags_per_tool strat) 5)
        max-desc (or (:max_flag_desc_chars strat) 75)
        min-score (or (:min_candidate_score strat) 40)
        q-info (tokenize-query intent keyword strat)
        path-execs (scan-path-executables)
        kw-tok (:keyword-token q-info)
        exact-match? (boolean (and kw-tok (contains? path-execs kw-tok)))
        eff-limit (cond
                    limit (min max-cand (max 1 (int limit)))
                    exact-match? (or (:exact_binary_limit strat) 1)
                    :else (or (:default_limit strat) 2))
        dpkg-map (load-dpkg-status)
        gathered (gather-candidates q-info strat dpkg-map path-execs)
        top-pre (->> (:cmds gathered)
                     (map (fn [cmd]
                            {:cmd cmd
                             :pre-score (pre-score-candidate cmd q-info gathered path-execs weights)}))
                     (sort-by (juxt (comp - :pre-score) :cmd))
                     (take (max 10 (* eff-limit 3)))
                     (mapv #(resolve-exec-info (get path-execs (:cmd %)))))
        provenance (resolve-dpkg-provenance top-pre dpkg-map)
        cache-atom (atom (load-discovery-cache strat))
        before-cache @cache-atom
        enriched (mapv (fn [exec-info]
                         (let [cmd (:cmd exec-info)
                               prov (get provenance cmd)
                               iface (extract-interface exec-info (:package_summary prov) strat cache-atom)
                               summary-l (str/lower-case (or (:summary iface) ""))
                               flags-l (str/lower-case (str/join " " (map #(str (:spec %) " " (:desc %)) (:flags iface))))
                               pkg-l (str/lower-case (or (:package prov) ""))
                               pre (pre-score-candidate cmd q-info gathered path-execs weights)
                               final-score (+ pre
                                              (if (some #(str/includes? pkg-l %) (:primary q-info)) (or (:package_name_match weights) 50) 0)
                                              (* (count (filter #(str/includes? summary-l %) (:primary q-info)))
                                                 (or (:intent_token_in_summary weights) 15))
                                              (* (min 4 (count (filter #(str/includes? flags-l %) (:primary q-info))))
                                                 (or (:intent_token_in_flags weights) 12))
                                              (if (str/starts-with? (:interface_source iface) "man(")
                                                (or (:has_man_page weights) 10)
                                                0)
                                              (if (= "dpkg" (:manager prov))
                                                (or (:dpkg_managed weights) 8)
                                                0))]
                           {:cmd cmd
                            :score final-score
                            :exec exec-info
                            :prov prov
                            :iface iface}))
                       top-pre)
        _ (when (not= before-cache @cache-atom)
            (save-discovery-cache! strat @cache-atom))
        sorted-enriched (sort-by (juxt (comp - :score) :cmd) enriched)
        filtered-enriched (let [above (filter #(>= (:score %) min-score) sorted-enriched)]
                            (if (seq above)
                              above
                              (take 1 sorted-enriched)))
        ranked (take eff-limit filtered-enriched)
        results-map (reduce (fn [acc {:keys [cmd exec prov iface]}]
                              (let [safety (get-in strat [:command_safety (clojure.core/keyword cmd)])
                                    side-effects (:side_effects safety)
                                    usage-order (:usage_order safety)
                                    dry-run (:dry_run_flag iface)
                                    entry (cond-> {:binary (:realpath exec)}
                                            (:package prov) (assoc :package (:package prov))
                                            true (assoc :manager (:manager prov))
                                            (:version prov) (assoc :version (:version prov))
                                            true (assoc :interface_source (:interface_source iface)
                                                        :summary (:summary iface))
                                            side-effects (assoc :side_effects side-effects)
                                            dry-run (assoc :dry_run_flag dry-run)
                                            usage-order (assoc :usage_order usage-order)
                                            true (assoc :flags (select-relevant-flags (:flags iface)
                                                                                      (:primary q-info)
                                                                                      max-flags
                                                                                      max-desc)))]
                                (assoc acc (clojure.core/keyword cmd) entry)))
                            (array-map)
                            ranked)
        not-installed (when (and kw-tok (not exact-match?))
                        [kw-tok])]
    (cond-> {:RESULTS results-map}
      (seq not-installed) (assoc :NOT_INSTALLED not-installed))))
