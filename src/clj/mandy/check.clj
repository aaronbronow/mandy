(ns mandy.check
  (:require [clojure.string :as str]
            [mandy.strategy :as strategy]
            [mandy.ubuntu :as ubuntu]))

(def ^:private shell-builtins
  #{"." ":" "[" "[[" "alias" "bg" "break" "builtin" "case" "cd" "command" "continue"
    "declare" "do" "done" "echo" "elif" "else" "enable" "esac" "eval" "exec" "exit"
    "export" "false" "fg" "fi" "for" "function" "getopts" "hash" "if" "in" "jobs"
    "kill" "let" "local" "logout" "printf" "pwd" "read" "readonly" "return" "select"
    "set" "shift" "shopt" "source" "test" "then" "time" "trap" "true" "type" "typeset"
    "ulimit" "umask" "unalias" "unset" "until" "wait" "while"})

(def ^:private cli-long-flag-checked
  #{"rg" "grep" "tar" "rsync" "zstd" "b2sum" "tree" "find" "ys" "busybox"
    "sqlite3" "jq" "pdftotext" "pdfunite" "ffmpeg" "ls" "cp" "mv" "rm"
    "sed" "awk" "gawk" "curl" "wget"})

(defn- extract-subshells
  "Return [cleaned-str subshell-cmds] extracting top-level $(...) commands (skipping $((...)))."
  [s]
  (let [n (count s)]
    (loop [i 0
           quote nil
           esc? false
           out (StringBuilder.)
           subs []]
      (if (>= i n)
        [(.toString out) subs]
        (let [c (.charAt s i)
              nxt (when (< (inc i) n) (.charAt s (inc i)))]
          (cond
            esc?
            (do (.append out c)
                (recur (inc i) quote false out subs))

            (and (= c \\) (not= quote :single))
            (do (.append out c)
                (recur (inc i) quote true out subs))

            (and (= c \') (nil? quote))
            (do (.append out c)
                (recur (inc i) :single false out subs))

            (and (= c \') (= quote :single))
            (do (.append out c)
                (recur (inc i) nil false out subs))

            (and (= c \") (nil? quote))
            (do (.append out c)
                (recur (inc i) :double false out subs))

            (and (= c \") (= quote :double))
            (do (.append out c)
                (recur (inc i) nil false out subs))

            (and (not= quote :single) (= c \$) (= nxt \()
                 (not (and (< (+ i 2) n) (= (.charAt s (+ i 2)) \())))
            (let [inner-res
                  (loop [j (+ i 2) depth 1 q2 nil esc2? false inner (StringBuilder.)]
                    (if (>= j n)
                      [j (.toString inner)]
                      (let [ch (.charAt s j)]
                        (cond
                          esc2? (do (.append inner ch) (recur (inc j) depth q2 false inner))
                          (and (= ch \\) (not= q2 :single)) (do (.append inner ch) (recur (inc j) depth q2 true inner))
                          (and (= ch \') (nil? q2)) (do (.append inner ch) (recur (inc j) depth :single false inner))
                          (and (= ch \') (= q2 :single)) (do (.append inner ch) (recur (inc j) depth nil false inner))
                          (and (= ch \") (nil? q2)) (do (.append inner ch) (recur (inc j) depth :double false inner))
                          (and (= ch \") (= q2 :double)) (do (.append inner ch) (recur (inc j) depth nil false inner))
                          (and (nil? q2) (= ch \()) (do (.append inner ch) (recur (inc j) (inc depth) q2 false inner))
                          (and (nil? q2) (= ch \))) (if (= depth 1)
                                                      [(inc j) (.toString inner)]
                                                      (do (.append inner ch) (recur (inc j) (dec depth) q2 false inner)))
                          :else (do (.append inner ch) (recur (inc j) depth q2 false inner))))))
                  [next-idx inner-str] inner-res]
              (.append out "SUBSHELL_VAL")
              (recur next-idx quote false out (conj subs inner-str)))

            :else
            (do (.append out c)
                (recur (inc i) quote false out subs))))))))

(defn- tokenize-shell
  "Quote-aware shell tokenizer yielding {:type :word|:sep|:redir :text s}."
  [s]
  (let [n (count s)]
    (loop [i 0
           quote nil
           esc? false
           cur (StringBuilder.)
           had-cur? false
           tokens []]
      (let [flush-word (fn [toks]
                         (if (or had-cur? (pos? (.length cur)))
                           (conj toks {:type :word :text (.toString cur)})
                           toks))]
        (if (>= i n)
          (flush-word tokens)
          (let [c (.charAt s i)
                nxt (when (< (inc i) n) (.charAt s (inc i)))]
            (cond
              esc?
              (do (.append cur c)
                  (recur (inc i) quote false cur true tokens))

              (and (= c \\) (not= quote :single))
              (recur (inc i) quote true cur true tokens)

              (and (= c \') (nil? quote))
              (recur (inc i) :single false cur true tokens)

              (and (= c \') (= quote :single))
              (recur (inc i) nil false cur true tokens)

              (and (= c \") (nil? quote))
              (recur (inc i) :double false cur true tokens)

              (and (= c \") (= quote :double))
              (recur (inc i) nil false cur true tokens)

              ;; Comment outside quotes
              (and (nil? quote) (= c \#) (not had-cur?) (zero? (.length cur)))
              (let [nl (.indexOf s "\n" i)]
                (if (neg? nl)
                  tokens
                  (recur nl nil false cur false tokens)))

              ;; Whitespace outside quotes
              (and (nil? quote) (Character/isWhitespace c))
              (let [toks' (flush-word tokens)
                    toks'' (if (= c \newline)
                             (if (and (seq toks') (not= :sep (:type (peek toks'))))
                               (conj toks' {:type :sep :text "\n"})
                               toks')
                             toks')]
                (recur (inc i) nil false (StringBuilder.) false toks''))

              ;; Operators outside quotes
              (and (nil? quote) (or (and (= c \&) (= nxt \&))
                                    (and (= c \|) (= nxt \|))))
              (let [op (str c nxt)]
                (recur (+ i 2) nil false (StringBuilder.) false
                       (conj (flush-word tokens) {:type :sep :text op})))

              (and (nil? quote) (= c \&) (= nxt \>))
              (recur (+ i 2) nil false (StringBuilder.) false
                     (conj (flush-word tokens) {:type :redir :text "&>"}))

              (and (nil? quote) (or (= c \;) (= c \|)))
              (recur (inc i) nil false (StringBuilder.) false
                     (conj (flush-word tokens) {:type :sep :text (str c)}))

              (and (nil? quote) (= c \>) (= nxt \>))
              (recur (+ i 2) nil false (StringBuilder.) false
                     (conj (flush-word tokens) {:type :redir :text ">>"}))

              (and (nil? quote) (or (= c \>) (= c \<)))
              (recur (inc i) nil false (StringBuilder.) false
                     (conj (flush-word tokens) {:type :redir :text (str c)}))

              :else
              (do (.append cur c)
                  (recur (inc i) quote false cur true tokens)))))))))

(defn- parse-segment-tokens [toks]
  (let [n (count toks)]
    (loop [i 0
           words []
           redirs []]
      (if (>= i n)
        (let [;; Strip leading VAR=val assignments
              no-env (drop-while #(re-matches #"^[A-Za-z_][A-Za-z0-9_]*=.*" %) words)
              ;; Handle optional shell/wrapper keywords preceding an inner command
              no-wrap (loop [xs no-env]
                        (if (and (> (count xs) 1) (#{"command" "time" "nohup" "do" "then" "else"} (first xs)))
                          (recur (rest xs))
                          xs))]
          {:cmd (first no-wrap)
           :args (vec (rest no-wrap))
           :redirs redirs})
        (let [{:keys [type text]} (nth toks i)]
          (cond
            (= type :redir)
            (let [nxt (when (< (inc i) n) (nth toks (inc i)))
                  target (when (and nxt (= :word (:type nxt))) (:text nxt))]
              (recur (if target (+ i 2) (inc i))
                     words
                     (if target (conj redirs {:op text :target target}) redirs)))

            ;; Handle 2>/dev/null or 1>file attached to word
            (and (= type :word) (re-matches #"^[012]&?(>>?|<)(.+)?$" text))
            (let [[_ op inline-target] (re-matches #"^[012]&?(>>?|<)(.+)?$" text)]
              (if inline-target
                (recur (inc i) words (conj redirs {:op op :target inline-target}))
                (let [nxt (when (< (inc i) n) (nth toks (inc i)))
                      target (when (and nxt (= :word (:type nxt))) (:text nxt))]
                  (recur (if target (+ i 2) (inc i))
                         words
                         (if target (conj redirs {:op op :target target}) redirs)))))

            ;; Handle >file attached without space
            (and (= type :word) (re-matches #"^(>>?|<)(.+)$" text))
            (let [[_ op target] (re-matches #"^(>>?|<)(.+)$" text)]
              (recur (inc i) words (conj redirs {:op op :target target})))

            :else
            (recur (inc i) (conj words text) redirs)))))))

(defn split-shell-segments
  "Parse command string into command segments (and recursively parsed $(...) subshells)."
  [cmd-str]
  (let [[cleaned subshells] (extract-subshells (or cmd-str ""))
        toks (tokenize-shell cleaned)
        raw-segs (reduce (fn [acc {:keys [type] :as tok}]
                           (if (= type :sep)
                             (conj acc [])
                             (update acc (dec (count acc)) conj tok)))
                         [[]]
                         toks)
        main-segs (->> raw-segs
                       (remove empty?)
                       (mapv parse-segment-tokens)
                       (filterv #(not (str/blank? (:cmd %)))))
        sub-segs (mapcat split-shell-segments subshells)]
    (vec (concat main-segs sub-segs))))

;; --- Guardrail Checks ---

(defn- norm-cmd-name [cmd]
  (when cmd
    (last (str/split cmd #"/"))))

(defn- tar-create-info [args]
  (let [takes-arg #{"-f" "--file" "-C" "--directory" "-T" "--files-from" "-X" "--exclude-from"
                    "--exclude" "-I" "--use-compress-program" "-b" "--blocking-factor"}
        n (count args)]
    (loop [i 0
           expect-archive? false
           skip-next? false
           create? false
           has-z? false
           has-zstd? false
           has-xz? false
           has-auto? false
           archive-file nil
           pos-paths []
           exclude-after-pos? false]
      (if (>= i n)
        {:create? create?
         :has-z? has-z?
         :has-zstd? has-zstd?
         :has-xz? has-xz?
         :has-auto? has-auto?
         :archive-file archive-file
         :exclude-after-pos? exclude-after-pos?}
        (let [a (nth args i)]
          (cond
            expect-archive?
            (recur (inc i) false false create? has-z? has-zstd? has-xz? has-auto? a pos-paths exclude-after-pos?)

            skip-next?
            (recur (inc i) false false create? has-z? has-zstd? has-xz? has-auto? archive-file pos-paths exclude-after-pos?)

            (= a "--")
            (recur n false false create? has-z? has-zstd? has-xz? has-auto? archive-file pos-paths exclude-after-pos?)

            (str/starts-with? a "--")
            (let [[flag val] (str/split a #"=" 2)
                  is-exclude? (= flag "--exclude")
                  is-file? (= flag "--file")
                  c? (or create? (= flag "--create"))
                  z? (or has-z? (= flag "--gzip") (= flag "--ungzip"))
                  zst? (or has-zstd? (= flag "--zstd") (= flag "--use-compress-program"))
                  xz? (or has-xz? (= flag "--xz"))
                  auto? (or has-auto? (= flag "--auto-compress"))
                  ex-after? (or exclude-after-pos? (and is-exclude? (seq pos-paths)))]
              (recur (inc i)
                     (and is-file? (nil? val))
                     (and (contains? takes-arg flag) (not is-file?) (nil? val))
                     c? z? zst? xz? auto?
                     (if (and is-file? val) val archive-file)
                     pos-paths
                     ex-after?))

            (and (str/starts-with? a "-") (> (count a) 1))
            (let [chars (subs a 1)
                  c? (or create? (str/includes? chars "c"))
                  z? (or has-z? (str/includes? chars "z"))
                  xz? (or has-xz? (str/includes? chars "J"))
                  auto? (or has-auto? (str/includes? chars "a"))
                  zst? (or has-zstd? (str/includes? chars "I"))
                  ends-f? (str/ends-with? chars "f")
                  needs-arg? (and (not ends-f?) (contains? takes-arg a))]
              (recur (inc i) ends-f? needs-arg? c? z? zst? xz? auto? archive-file pos-paths exclude-after-pos?))

            :else
            (recur (inc i) false false create? has-z? has-zstd? has-xz? has-auto? archive-file (conj pos-paths a) exclude-after-pos?)))))))

(defn- rsync-flags-info [args]
  (let [has-delete? (some #(or (= % "--delete")
                               (str/starts-with? % "--delete-"))
                          args)
        has-dry-run? (some (fn [a]
                             (or (= a "--dry-run")
                                 (and (str/starts-with? a "-")
                                      (not (str/starts-with? a "--"))
                                      (str/includes? (subs a 1) "n"))))
                           args)]
    {:has-delete? (boolean has-delete?)
     :has-dry-run? (boolean has-dry-run?)}))

(defn- check-unknown-long-flags [cmd-name args path-execs strat cache-atom]
  (when (contains? cli-long-flag-checked cmd-name)
    (when-let [raw-exec (get path-execs cmd-name)]
      (let [exec-info (ubuntu/resolve-exec-info raw-exec)
            iface (ubuntu/extract-interface exec-info nil strat cache-atom)
            known (set (:known_long_flags iface))]
        (when (>= (count known) 5)
          (let [long-flags (->> args
                                (take-while #(not= % "--"))
                                (filter #(and (str/starts-with? % "--") (> (count %) 2)))
                                (map #(first (str/split % #"=" 2)))
                                distinct)]
            (keep (fn [lf]
                    (let [pos-variant (when (str/starts-with? lf "--no-")
                                        (str "--" (subs lf 5)))]
                      (when-not (or (contains? known lf)
                                    (and pos-variant (contains? known pos-variant)))
                        {:code "UNKNOWN_FLAG"
                         :severity "block"
                         :command cmd-name
                         :message (str "Flag '" lf "' is not documented for '" cmd-name "' (" (:interface_source iface) "). Call discover_local_tool(intent, keyword='" cmd-name "') for valid flags.")})))
                  long-flags)))))))

(defn- norm-path-token [s]
  (-> (or s "")
      (str/replace #"^\./" "")
      str/trim))

(defn check-command
  "Deterministically validate a proposed bash command before execution."
  ([cmd-str] (check-command cmd-str {}))
  ([cmd-str {:keys [session-state strict-preview? block-pkg-installs?]
             :or {strict-preview? true block-pkg-installs? true}}]
   (let [strat (strategy/load-ubuntu-strategy)
         path-execs (ubuntu/scan-path-executables)
         cache-atom (atom (ubuntu/load-discovery-cache strat))
         before-cache @cache-atom
         segments (split-shell-segments cmd-str)
         blocked-pms (set (map str/lower-case (or (:blocked_package_managers strat) [])))
         previewed (if session-state (or (:previewed @session-state) #{}) #{})
         findings (atom [])
         previews-recorded (atom [])]

     ;; 1. Cross-segment destructive chain check (e.g. rsync/cp && find ... -exec rm / -delete)
     (let [had-sync? (atom false)]
       (doseq [{:keys [cmd args]} segments]
         (let [cname (norm-cmd-name cmd)]
           (when (#{"rsync" "cp" "mv"} cname)
             (reset! had-sync? true))
           (when (and @had-sync?
                      (= cname "find")
                      (or (some #{"-delete"} args)
                          (and (some #{"-exec" "-execdir"} args)
                               (some #{"rm" "/bin/rm" "/usr/bin/rm"} args))))
             (swap! findings conj
                    {:code "DESTRUCTIVE_CHAIN"
                     :severity "block"
                     :command "find"
                     :message "Blocked destructive 'find -delete / -exec rm' chained after copy/sync. Use 'rsync --delete' after previewing with '--dry-run' (-n)."})))))

     ;; 2. Per-segment checks
     (doseq [{:keys [cmd args redirs]} segments]
       (let [cname (norm-cmd-name cmd)
             builtin? (contains? shell-builtins cname)
             local-path? (or (str/starts-with? cmd "/") (str/starts-with? cmd "./"))]
         (when-not builtin?
           ;; Package manager install block
           (when (and block-pkg-installs? (contains? blocked-pms cname)
                      (some #{"install" "-i" "upgrade"} args))
             (swap! findings conj
                    {:code "PACKAGE_INSTALL_BLOCKED"
                     :severity "block"
                     :command cname
                     :message (str "Installing packages via '" cname "' is blocked by harness policy. Use discover_local_tool to find already-installed alternatives.")}))

           ;; Missing binary check
           (let [exists? (if local-path?
                           (let [f (clojure.java.io/file cmd)]
                             (and (.exists f) (.canExecute f)))
                           (contains? path-execs cname))]
             (if-not exists?
               (swap! findings conj
                      {:code "MISSING_BINARY"
                       :severity "block"
                       :command cname
                       :message (str "Binary '" cname "' is not installed locally. Call discover_local_tool(intent, keyword='" cname "') instead of guessing.")})
               (do
                 ;; Destructive rm targets
                 (when (and (= cname "rm")
                            (some #(or (= % "--recursive")
                                       (and (str/starts-with? % "-")
                                            (not (str/starts-with? % "--"))
                                            (str/includes? (subs % 1) "r")))
                                  args)
                            (some #(re-matches #"^(/|/\*|\.\.(?:/.*)?|~(?:/.*)?|\*)$" %) args))
                   (swap! findings conj
                          {:code "DESTRUCTIVE_RM"
                           :severity "block"
                           :command "rm"
                           :message "Blocked recursive 'rm' targeting root, parent, home, or top-level wildcard."}))

                 ;; tar option order & archive format mismatch
                 (when (= cname "tar")
                   (let [{:keys [create? has-z? has-zstd? has-xz? has-auto? archive-file exclude-after-pos?]}
                         (tar-create-info args)]
                     (when (and create? exclude-after-pos?)
                       (swap! findings conj
                              {:code "FLAG_ORDER_ERROR"
                               :severity "block"
                               :command "tar"
                               :message "GNU tar requires '--exclude' options BEFORE source paths (e.g. 'tar --exclude=... -czf archive.tar.gz dir/')."}))
                     (when (and create? archive-file (not has-auto?))
                       (cond
                         (and (or (str/ends-with? archive-file ".tar.zst") (str/ends-with? archive-file ".tzst"))
                              (not has-zstd?))
                         (swap! findings conj
                                {:code "ARCHIVE_FORMAT_MISMATCH"
                                 :severity "block"
                                 :command "tar"
                                 :message (str "Archive '" archive-file "' expects zstd compression, but '--zstd' (or '-a') was omitted.")})

                         (and (or (str/ends-with? archive-file ".tar.gz") (str/ends-with? archive-file ".tgz"))
                              (not has-z?))
                         (swap! findings conj
                                {:code "ARCHIVE_FORMAT_MISMATCH"
                                 :severity "block"
                                 :command "tar"
                                 :message (str "Archive '" archive-file "' expects gzip compression, but '-z'/'--gzip' (or '-a') was omitted.")})

                         (and (or (str/ends-with? archive-file ".tar.xz") (str/ends-with? archive-file ".txz"))
                              (not has-xz?))
                         (swap! findings conj
                                {:code "ARCHIVE_FORMAT_MISMATCH"
                                 :severity "block"
                                 :command "tar"
                                 :message (str "Archive '" archive-file "' expects xz compression, but '-J'/'--xz' (or '-a') was omitted.")})))))

                 ;; rsync --delete preview gate
                 (when (= cname "rsync")
                   (let [{:keys [has-delete? has-dry-run?]} (rsync-flags-info args)]
                     (when (and has-delete? strict-preview?)
                       (if has-dry-run?
                         (swap! previews-recorded conj "rsync")
                         (when-not (contains? previewed "rsync")
                           (swap! findings conj
                                  {:code "UNPREVIEWED_MUTATION"
                                   :severity "block"
                                   :command "rsync"
                                   :message "rsync with '--delete' removes destination files. Run a preview with '--dry-run' (-n) first in this session, then run without '--dry-run'."}))))))

                 ;; In-place shell redirect truncation (> same file)
                 (let [overwrite-targets (->> redirs
                                              (filter #(contains? #{">" "&>"} (:op %)))
                                              (map #(norm-path-token (:target %)))
                                              (remove #(or (str/blank? %) (= % "/dev/null")))
                                              set)
                       pos-args (->> args
                                     (remove #(str/starts-with? % "-"))
                                     (map norm-path-token)
                                     set)]
                   (doseq [t overwrite-targets]
                     (when (contains? pos-args t)
                       (swap! findings conj
                              {:code "INPLACE_REDIRECT_TRUNCATION"
                               :severity "block"
                               :command cname
                               :message (str "Shell redirection '> " t "' truncates '" t "' before '" cname "' can read it. Use an in-place flag (e.g. 'sed -i') or a temporary file.")}))))

                 ;; Unknown long flag check against live cached man/--help flags
                 (doseq [f (check-unknown-long-flags cname args path-execs strat cache-atom)]
                   (swap! findings conj f))))))))

     (when (not= before-cache @cache-atom)
       (ubuntu/save-discovery-cache! strat @cache-atom))

     (let [fs @findings
           recs (vec (distinct @previews-recorded))
           blocked? (some #(= "block" (:severity %)) fs)]
       (when (and (not blocked?) session-state (seq recs))
         (swap! session-state update :previewed (fnil into #{}) recs))
       (cond-> {:status (if blocked? "block" "allow")
                :findings fs}
         (seq recs) (assoc :preview_recorded recs))))))

(defn format-check-text
  "Render compact guardrail verdict for MCP or hook stderr output."
  [{:keys [status findings preview_recorded]}]
  (let [head (str (str/upper-case status)
                  (when (seq preview_recorded)
                    (str " (recorded dry-run preview: " (str/join ", " preview_recorded) ")")))]
    (if (empty? findings)
      head
      (str head "\n"
           (str/join "\n"
                     (map (fn [{:keys [code command message]}]
                            (str "- [" code "] " command ": " message))
                          findings))))))
