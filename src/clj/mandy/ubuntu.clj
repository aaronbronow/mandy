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

;; --- Fast In-Memory Caches (dir-mtime L1 + disk-mirror + pre-encoded L2) ---

(defonce ^:private path-execs-cache (atom nil))
(defonce ^:private dpkg-status-cache (atom nil))
(defonce ^:private disk-cache-mem (atom nil))
(defonce ^:private response-cache (atom {}))

(defn path-dir-signature
  "Return [[dir mtime] ...] across PATH directories for sub-millisecond invalidation."
  []
  (let [path-env (or (System/getenv "PATH") "/usr/bin:/bin")]
    (mapv (fn [dir-str]
            (let [^File d (io/file dir-str)]
              [dir-str (if (.exists d) (.lastModified d) -1)]))
          (remove str/blank? (str/split path-env #":")))))

(defn dpkg-status-mtime
  "Return mtime of /var/lib/dpkg/status (or -1 if absent)."
  []
  (let [^File f (io/file "/var/lib/dpkg/status")]
    (if (.exists f) (.lastModified f) -1)))

;; --- Local PATH Executable Verification ---

(defn- executable-file? [^File f]
  (when f
    (try
      (let [p (.toPath f)]
        (and (Files/isRegularFile p (make-array LinkOption 0))
             (Files/isExecutable p)))
      (catch Exception _ false))))

(defn scan-path-executables
  "Scan PATH directories and return {cmd-name {:cmd :path}}, memoized by dir mtime and TTL."
  ([] (scan-path-executables 30000))
  ([ttl-ms]
   (let [now (System/currentTimeMillis)
         sig (path-dir-signature)
         cached @path-execs-cache]
     (if (and cached
              (= (:sig cached) sig)
              (< now (long (:expires-ms cached))))
       (:data cached)
       (let [data (reduce (fn [acc [dir-str _]]
                            (let [^File dir (io/file dir-str)]
                              (if (and (.exists dir) (.isDirectory dir))
                                (reduce (fn [m ^File f]
                                          (let [nm (.getName f)]
                                            (if (and (not (contains? m nm))
                                                     (executable-file? f))
                                              (assoc m nm {:cmd nm :path (.getAbsolutePath f)})
                                              m)))
                                        acc
                                        (or (.listFiles dir) []))
                                acc)))
                          {}
                          sig)]
         (reset! path-execs-cache {:sig sig
                                   :expires-ms (+ now (long (or ttl-ms 30000)))
                                   :data data})
         data)))))

(defn resolve-exec-info
  "Resolve realpath and mtime for a verified PATH executable map."
  [{:keys [cmd path] :as entry}]
  (when entry
    (try
      (let [p (Paths/get path (into-array String []))
            rp (.toString (.toRealPath p (into-array LinkOption [])))
            ^File rf (io/file rp)
            mtime (.lastModified rf)]
        (assoc entry :realpath rp :mtime mtime))
      (catch Exception _
        (let [^File pf (io/file path)]
          (assoc entry :realpath path :mtime (.lastModified pf)))))))

;; --- Offline dpkg Metadata & Provenance ---

(defn- parse-dpkg-status-text [^String text]
  (let [stanzas (str/split text #"\n\n+")]
    (reduce (fn [acc stanza]
              (let [lines (str/split-lines stanza)
                    parsed (reduce (fn [{:keys [fields last-key]} ^String line]
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
  (let [^File f (io/file "/var/lib/dpkg/status")]
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
  (let [^File f (cache-file strategy)
        fpath (.getAbsolutePath f)]
    (if-not (.exists f)
      (do (reset! disk-cache-mem nil)
          {})
      (let [mtime (.lastModified f)
            cached @disk-cache-mem]
        (if (and cached
                 (= (:path cached) fpath)
                 (= (:mtime cached) mtime))
          (:data cached)
          (let [data (try
                       (json/parse-string (slurp f) false)
                       (catch Exception _ {}))]
            (reset! disk-cache-mem {:path fpath :mtime mtime :data data})
            data))))))

(defn save-discovery-cache! [strategy cache-map]
  (let [^File f (cache-file strategy)
        ^File parent (.getParentFile f)]
    (try
      (.mkdirs parent)
      (let [^File tmp (File/createTempFile "ubuntu-cache-" ".json" parent)]
        (spit tmp (json/generate-string cache-map))
        (Files/move (.toPath tmp) (.toPath f)
                    (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING
                                                    StandardCopyOption/ATOMIC_MOVE]))
        (reset! disk-cache-mem {:path (.getAbsolutePath f)
                                :mtime (.lastModified f)
                                :data cache-map}))
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
             (= (get cached "schema_version") 3)
             (= (get cached "mtime") mtime))
      {:interface_source (get cached "interface_source")
       :summary (get cached "summary")
       :flags (mapv (fn [f] {:spec (get f "spec") :desc (get f "desc")}) (get cached "flags"))
       :known_long_flags (vec (get cached "known_long_flags"))
       :dry_run_flag (get cached "dry_run_flag")
       :cached_prov (when (contains? cached "dpkg_status_mtime")
                      {:dpkg_status_mtime (get cached "dpkg_status_mtime")
                       :package (get cached "package")
                       :manager (get cached "manager")
                       :version (get cached "version")
                       :package_summary (get cached "package_summary")})
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
            record (cond-> {"schema_version" 3
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
        kw-tok (when-not (str/blank? keyword-str)
                 (let [k (str/trim (str/lower-case keyword-str))]
                   (when (re-matches #"[a-z0-9][a-z0-9_+.-]*" k) k)))
        kw-syns (set (map str/lower-case (or (when kw-tok (get syns (keyword kw-tok))) [])))
        raw-text (str/lower-case (str (or keyword-str "") " " (or intent "")))
        primary (->> (concat (when kw-tok [kw-tok])
                             (re-seq #"[a-z0-9][a-z0-9_+]*" raw-text))
                     (remove #(or (contains? stops %) (< (count %) 2)))
                     distinct
                     vec)
        expanded (->> (concat kw-syns
                              (mapcat #(get syns (keyword %) []) primary))
                      (map str/lower-case)
                      (remove (set primary))
                      distinct
                      vec)]
    {:primary primary
     :expanded expanded
     :kw-synonyms kw-syns
     :all (vec (distinct (concat primary expanded)))
     :keyword-token kw-tok}))

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

(defn- pre-score-candidate [cmd {:keys [primary expanded kw-synonyms keyword-token]} {:keys [apropos dpkg-hits deep-set]} path-execs weights]
  (let [c-l (str/lower-case cmd)
        path (get-in path-execs [cmd :path] "")
        ap-desc (str/lower-case (or (get-in apropos [cmd :apropos-desc]) ""))
        dpkg-score (get dpkg-hits cmd 0)]
    (+ (if (= c-l keyword-token) (or (:exact_binary_match weights) 120) 0)
       (if (contains? kw-synonyms c-l) 110 0)
       (if (some #(= c-l %) primary) 35 0)
       (if (some #(= c-l %) expanded) (or (:binary_prefix_match weights) 60) 0)
       (if (some #(and (>= (count %) 3) (str/starts-with? c-l %)) primary) 30 0)
       (if (contains? apropos cmd) (or (:apropos_match weights) 40) 0)
       (* (min 5 (count (filter #(str/includes? ap-desc %) primary))) (or (:intent_token_in_summary weights) 15))
       (* (min 4 dpkg-score) (or (:dpkg_description_match weights) 30))
       (if (contains? deep-set cmd) (or (:man_deep_match weights) 25) 0)
       (if (str/starts-with? path "/usr/bin/") (or (:standard_usr_bin weights) 5) 0))))

(defn- text-word-set [^String s]
  (let [words (re-seq #"[a-z0-9]+" (str/lower-case (or s "")))]
    (reduce (fn [acc ^String w]
              (cond-> (conj acc w)
                (and (> (count w) 3) (str/ends-with? w "s"))
                (conj (subs w 0 (dec (count w))))))
            #{}
            words)))

(defn- select-relevant-flags
  [flags primary-tokens max-flags max-desc-chars & {:keys [lean? unmatched-cap has-usage-order?]}]
  (let [wants-meta? (some #{"help" "version" "license" "formats"} primary-tokens)
        pt-stems (mapv (fn [^String pt]
                         (if (and (> (count pt) 3) (str/ends-with? pt "s"))
                           [pt (subs pt 0 (dec (count pt)))]
                           [pt nil]))
                       primary-tokens)
        scored (map-indexed
                 (fn [idx {:keys [spec desc]}]
                   (let [spec-ws (text-word-set spec)
                         desc-ws (text-word-set desc)
                         spec-matched (set (keep (fn [[pt st]]
                                                   (when (or (contains? spec-ws pt)
                                                             (and st (contains? spec-ws st)))
                                                     pt))
                                                 pt-stems))
                         desc-matched (set (keep (fn [[pt st]]
                                                   (when (or (contains? desc-ws pt)
                                                             (and st (contains? desc-ws st)))
                                                     pt))
                                                 pt-stems))
                         all-matched (into spec-matched desc-matched)
                         has-short? (boolean (re-find #"^-[a-zA-Z0-9](\s|,|$)" spec))
                         concise? (<= (count spec) 26)
                         meta-pen (if (and (not wants-meta?)
                                           (or (re-find #"^(?:-h|-V|--help|--version|-version|-buildconf|-formats|-demuxers|-muxers|-devices|-codecs|-decoders|-encoders|-bsfs|-h,|-D\s|-Olevel|-warn|-nowarn)(?:\s|,|$)" spec)
                                               (re-find #"^\s*(-P|-H)\s*$" spec)))
                                    -3
                                    0)
                         base-score (+ (* 4 (count spec-matched))
                                       (* 2 (count desc-matched))
                                       (if has-short? 1 0)
                                       (if concise? 1 0)
                                       meta-pen)]
                     {:idx idx :base-score base-score :matched all-matched :spec spec :desc desc}))
                 flags)
        matched-items (filter #(seq (:matched %)) scored)
        chosen (if (seq matched-items)
                 (let [picked (loop [rem matched-items
                                     covered #{}
                                     acc []]
                                (if (or (empty? rem) (>= (count acc) (long max-flags)))
                                  acc
                                  (let [best (apply max-key
                                                    (fn [item]
                                                      (let [new-cov (count (remove covered (:matched item)))]
                                                        (- (* 10000 (long (+ (* 8 new-cov) (:base-score item))))
                                                           (long (:idx item)))))
                                                    rem)]
                                    (recur (remove #(= (:idx %) (:idx best)) rem)
                                           (into covered (:matched best))
                                           (conj acc best)))))
                       picked-ids (set (map :idx picked))
                       topped (if (and (not lean?) (< (count picked) (long max-flags)))
                                (concat picked
                                        (->> scored
                                             (remove #(contains? picked-ids (:idx %)))
                                             (sort-by (juxt (comp - :base-score) :idx))
                                             (take (- (long max-flags) (count picked)))))
                                picked)]
                   (sort-by :idx topped))
                 (let [cap (if lean?
                             (if has-usage-order? 0 (or unmatched-cap 1))
                             max-flags)]
                   (if (pos? (long cap))
                     (->> scored
                          (sort-by (juxt (comp - :base-score) :idx))
                          (take cap)
                          (sort-by :idx))
                     [])))]
    (mapv (fn [{:keys [spec desc]}]
            (let [d (if (> (count desc) (long max-desc-chars))
                      (str (subs desc 0 (- (long max-desc-chars) 3)) "...")
                      desc)]
              (str spec ": " d)))
          chosen)))

(defn format-discovery-yaml
  "Render Agent-First YAML output (Lean by default; !yamlscript/v0/data when verbose)."
  ([result-map] (format-discovery-yaml result-map false))
  ([result-map verbose?]
   (let [body (yaml/generate-string result-map :dumper-options {:flow-style :block})]
     (if verbose?
       (str "!yamlscript/v0/data\n" body)
       body))))

(defn- attach-cached-prov [cache-atom top-pre dpkg-mtime dpkg-map]
  (let [need-prov (reduce (fn [acc {:keys [cmd realpath mtime]}]
                            (let [c (get @cache-atom realpath)]
                              (if (and c
                                       (= (get c "schema_version") 3)
                                       (= (get c "mtime") mtime)
                                       (= (get c "dpkg_status_mtime") dpkg-mtime))
                                (assoc acc cmd {:package (get c "package")
                                                :manager (get c "manager")
                                                :version (get c "version")
                                                :package_summary (get c "package_summary")})
                                acc)))
                          {}
                          top-pre)
        missing-execs (remove #(contains? need-prov (:cmd %)) top-pre)
        fetched (if (seq missing-execs)
                  (resolve-dpkg-provenance missing-execs dpkg-map)
                  {})]
    {:prov-map (merge need-prov fetched)
     :fetched-cmds (set (keys fetched))}))

(defn discover
  "Run Ubuntu live tool discovery and return structured map with verified local executables."
  [{:keys [intent keyword limit verbose? max-flags] :or {intent "" keyword nil limit nil verbose? false max-flags nil}}]
  (let [strat (strategy/load-ubuntu-strategy)
        ttl-ms (or (:l1_cache_ttl_ms strat) 30000)
        lean? (not verbose?)
        weights (:weights strat)
        max-cand (or (:max_candidates strat) 5)
        eff-max-flags (or max-flags
                          (if lean?
                            (or (:lean_max_flags_per_tool strat) 3)
                            (or (:max_flags_per_tool strat) 5)))
        max-desc (if lean?
                   (or (:lean_max_flag_desc_chars strat) 48)
                   (or (:max_flag_desc_chars strat) 75))
        unmatched-cap (or (:lean_unmatched_flag_cap strat) 1)
        min-score (or (:min_candidate_score strat) 40)
        q-info (tokenize-query intent keyword strat)
        path-execs (scan-path-executables ttl-ms)
        kw-tok (:keyword-token q-info)
        exact-match? (boolean (and kw-tok (contains? path-execs kw-tok)))
        eff-limit (cond
                    limit (min max-cand (max 1 (int limit)))
                    exact-match? (or (:exact_binary_limit strat) 1)
                    :else (or (:default_limit strat) 2))
        fast-exact? (and exact-match? (= eff-limit 1))
        cache-atom (atom (load-discovery-cache strat))
        before-cache @cache-atom
        need-dpkg? (or (not fast-exact?) verbose?)
        dpkg-map (when need-dpkg? (load-dpkg-status))
        dpkg-mtime (if verbose? (dpkg-status-mtime) -1)
        gathered (when-not fast-exact?
                   (gather-candidates q-info strat dpkg-map path-execs))
        top-pre (if fast-exact?
                  [(resolve-exec-info (get path-execs kw-tok))]
                  (->> (:cmds gathered)
                       (map (fn [cmd]
                              {:cmd cmd
                               :pre-score (pre-score-candidate cmd q-info gathered path-execs weights)}))
                       (sort-by (juxt (comp - :pre-score) :cmd))
                       (take (max 10 (* eff-limit 3)))
                       (mapv #(resolve-exec-info (get path-execs (:cmd %))))))
        {:keys [prov-map fetched-cmds]} (if need-dpkg?
                                          (attach-cached-prov cache-atom top-pre dpkg-mtime dpkg-map)
                                          {:prov-map {} :fetched-cmds #{}})
        enriched (mapv (fn [exec-info]
                         (let [cmd (:cmd exec-info)
                               prov (get prov-map cmd)
                               iface (extract-interface exec-info (:package_summary prov) strat cache-atom)
                               _ (when (and verbose? (contains? fetched-cmds cmd) prov)
                                   (swap! cache-atom update (:realpath exec-info)
                                          (fn [rec]
                                            (cond-> (assoc (or rec {}) "dpkg_status_mtime" dpkg-mtime)
                                              (:package prov) (assoc "package" (:package prov))
                                              (:manager prov) (assoc "manager" (:manager prov))
                                              (:version prov) (assoc "version" (:version prov))
                                              (:package_summary prov) (assoc "package_summary" (:package_summary prov))))))
                               summary-l (str/lower-case (or (:summary iface) ""))
                               flags-l (str/lower-case (str/join " " (map #(str (:spec %) " " (:desc %)) (:flags iface))))
                               pkg-l (str/lower-case (or (:package prov) ""))
                               pre (if fast-exact?
                                     200
                                     (pre-score-candidate cmd q-info gathered path-execs weights))
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
        filtered-enriched (if fast-exact?
                            sorted-enriched
                            (let [above (filter #(>= (:score %) min-score) sorted-enriched)]
                              (if (seq above)
                                above
                                (take 1 sorted-enriched))))
        ranked (take eff-limit filtered-enriched)
        results-map (reduce (fn [acc {:keys [cmd exec prov iface]}]
                              (let [safety (get-in strat [:command_safety (clojure.core/keyword cmd)])
                                    side-effects (:side_effects safety)
                                    usage-order (:usage_order safety)
                                    dry-run (:dry_run_flag iface)
                                    flags-vec (select-relevant-flags (:flags iface)
                                                                     (:primary q-info)
                                                                     eff-max-flags
                                                                     max-desc
                                                                     :lean? lean?
                                                                     :unmatched-cap unmatched-cap
                                                                     :has-usage-order? (boolean usage-order))
                                    entry (if lean?
                                            (cond-> (array-map)
                                              usage-order (assoc :order usage-order)
                                              (and side-effects (not= side-effects "read_only")) (assoc :side_effects side-effects)
                                              (and dry-run (contains? #{"mutating" "destructive"} side-effects)) (assoc :dry_run dry-run)
                                              (and (empty? flags-vec) (nil? usage-order)) (assoc :summary (:summary iface))
                                              (seq flags-vec) (assoc :flags flags-vec))
                                            (cond-> {:binary (:realpath exec)}
                                              (:package prov) (assoc :package (:package prov))
                                              true (assoc :manager (:manager prov))
                                              (:version prov) (assoc :version (:version prov))
                                              true (assoc :interface_source (:interface_source iface)
                                                          :summary (:summary iface))
                                              side-effects (assoc :side_effects side-effects)
                                              dry-run (assoc :dry_run_flag dry-run)
                                              usage-order (assoc :usage_order usage-order)
                                              true (assoc :flags flags-vec)))]
                                (assoc acc (clojure.core/keyword cmd) entry)))
                            (array-map)
                            ranked)
        topic-only #{"pdf" "yaml" "json" "video" "audio" "archive" "compress"
                     "checksum" "hash" "regex" "search" "tree" "sqlite" "socket"
                     "port" "network" "directory" "frame" "merge" "blake2" "exif"}
        not-installed (when (and kw-tok (not exact-match?) (not (contains? topic-only kw-tok)))
                        [kw-tok])]
    (cond-> (array-map)
      (seq not-installed) (assoc :NOT_INSTALLED not-installed)
      true (assoc :RESULTS results-map))))

(defn discover-yaml
  "Run discovery and return YAML string backed by a short-lived dir-mtime-validated L2 response cache."
  [{:keys [intent keyword limit verbose? max-flags] :as opts}]
  (let [strat (strategy/load-ubuntu-strategy)
        ttl-ms (long (or (:l1_cache_ttl_ms strat) 30000))
        now (System/currentTimeMillis)
        path-execs (scan-path-executables ttl-ms)
        path-sig (path-dir-signature)
        norm-int (when-not (str/blank? intent)
                   (-> intent str/trim str/lower-case (str/replace #"\s+" " ")))
        norm-kw (when-not (str/blank? keyword)
                  (-> keyword str/trim str/lower-case (str/replace #"\s+" " ")))
        max-cand (or (:max_candidates strat) 5)
        exact-match? (boolean (and norm-kw (contains? path-execs norm-kw)))
        eff-limit (cond
                    limit (min max-cand (max 1 (int limit)))
                    exact-match? (or (:exact_binary_limit strat) 1)
                    :else (or (:default_limit strat) 2))
        v? (boolean verbose?)
        dk-mtime (if v? (dpkg-status-mtime) -1)
        cache-key [norm-int norm-kw eff-limit v? (int (or max-flags 0)) path-sig dk-mtime]
        hit (get @response-cache cache-key)]
    (if (and hit (< now (long (:expires-ms hit))))
      (:yaml hit)
      (let [yaml-str (format-discovery-yaml (discover (assoc opts :verbose? v?)) v?)]
        (swap! response-cache
               (fn [m]
                 (let [m' (if (>= (count m) 256) {} m)]
                   (assoc m' cache-key {:expires-ms (+ now ttl-ms) :yaml yaml-str}))))
        yaml-str))))

(def ^:private preflight-ignore-tokens
  #{"a" "an" "the" "installed" "local" "locally" "cli" "tool" "tools" "binary"
    "utility" "utilities" "bash" "shell" "command" "script" "file" "files"
    "text" "data" "line" "lines" "json" "yaml" "csv" "pdf" "http" "https"
    "stdout" "stdin" "output" "input" "directory" "folder" "path" "paths"
    "equivalent" "otherwise" "only" "columns" "column" "header" "headers"
    "row" "rows" "archive" "format" "integer" "value" "relative" "sorted"
    "one" "per" "every" "each" "another" "write" "inside" "just" "any"})

(def ^:private preflight-common-absent-candidates
  #{"fd" "yq" "exiftool" "7z" "bat" "eza" "fzf" "ripgrep" "miller" "mlr"})

(defn preflight
  "Zero-turn deterministic prompt preflight returning compact Lean YAML hints for absent, specialized, or man-less tools mentioned in prompt."
  [^String prompt]
  (if (str/blank? prompt)
    ""
    (let [strat (strategy/load-ubuntu-strategy)
          ttl-ms (or (:l1_cache_ttl_ms strat) 30000)
          path-execs (scan-path-executables ttl-ms)
          specialized (set (map str/lower-case (or (:preflight_specialized_tools strat) [])))
          backticks (map second (re-seq #"`([a-zA-Z0-9][a-zA-Z0-9_+-]{1,24})`" prompt))
          verb-nouns (map second (re-seq #"(?i)\b(?:using|via|run|call|invoke)\s+(?:(?:an?|the|any)\s+)?(?:locally\s+installed\s+|installed\s+|local\s+)?([a-zA-Z0-9][a-zA-Z0-9_+-]{1,24})\b" prompt))
          words (re-seq #"\b[a-zA-Z0-9][a-zA-Z0-9_+-]{1,24}\b" (str/lower-case prompt))
          explicit-absent (->> (concat backticks verb-nouns)
                               (map str/lower-case)
                               (remove preflight-ignore-tokens)
                               (filter #(re-matches #"[a-z0-9][a-z0-9_+-]*" %))
                               (remove #(contains? path-execs %)))
          known-absent (->> words
                            (filter preflight-common-absent-candidates)
                            (remove #(contains? path-execs %)))
          spec-hits (->> words
                         (filter #(and (contains? specialized %) (contains? path-execs %))))
          pdf-topic? (and (some #{"pdf"} words)
                          (not-any? #(str/starts-with? % "pdf") spec-hits))
          candidates (->> (concat explicit-absent known-absent spec-hits (when pdf-topic? ["pdf"]))
                          distinct
                          (take 2)
                          vec)]
      (if (empty? candidates)
        ""
        (->> candidates
             (map (fn [kw]
                    (str/trim (discover-yaml {:intent prompt
                                              :keyword kw
                                              :limit 1
                                              :verbose? false
                                              :max-flags 2}))))
             (remove str/blank?)
             (str/join "\n"))))))
