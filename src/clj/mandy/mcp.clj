(ns mandy.mcp
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [mandy.check :as check]
            [mandy.strategy :as strategy]
            [mandy.ubuntu :as ubuntu])
  (:import [java.io BufferedReader InputStreamReader]))

(def ^:private discover-tool-definition
  {:name "discover_local_tool"
   :description "Check local Ubuntu CLI binary & flags (Lean YAML)."
   :inputSchema {:type "object"
                 :properties {:intent {:type "string"
                                       :description "task"}
                              :keyword {:type "string"
                                        :description "cmd/topic"}
                              :verbose {:type "boolean"
                                        :description "full dpkg info"}}
                 :required ["intent"]}})

(def ^:private check-tool-definition
  {:name "check_local_command"
   :description "PreToolUse check on a proposed bash command before execution."
   :inputSchema {:type "object"
                 :properties {:command {:type "string"
                                        :description "shell command"}
                              :require_dry_run_preview {:type "boolean"}
                              :block_package_installs {:type "boolean"}
                              :enrich {:type "boolean"}}
                 :required ["command"]}})

(defn- write-response! [msg]
  (println (json/generate-string msg))
  (flush))

(defn- jsonrpc-result [id result]
  {:jsonrpc "2.0" :id id :result result})

(defn- jsonrpc-error [id code message]
  {:jsonrpc "2.0" :id id :error {:code code :message message}})

(defn- norm-query-part [s]
  (when-not (str/blank? s)
    (-> s str/trim str/lower-case (str/replace #"\s+" " "))))

(defn- handle-request [req seen-queries session-state expose-check?]
  (let [id (:id req)
        method (:method req)
        params (:params req)]
    (cond
      (= method "initialize")
      (do (reset! seen-queries {})
          (reset! session-state {:previewed #{}})
          (jsonrpc-result id {:protocolVersion "2024-11-05"
                              :capabilities {:tools {}}
                              :serverInfo {:name "mandy" :version "0.4.0-ubuntu"}}))

      (= method "notifications/mandy/reset_session")
      (do (reset! seen-queries {})
          (reset! session-state {:previewed #{}})
          nil)

      (or (nil? id) (= method "notifications/initialized"))
      nil

      (= method "ping")
      (jsonrpc-result id {})

      (= method "tools/list")
      (jsonrpc-result id {:tools (if expose-check?
                                   [discover-tool-definition check-tool-definition]
                                   [discover-tool-definition])})

      (= method "tools/call")
      (let [tool-name (:name params)
            args (:arguments params)]
        (cond
          (= tool-name "discover_local_tool")
          (try
            (let [intent (str (or (:intent args) ""))
                  kw (when-let [k (:keyword args)]
                       (when-not (str/blank? (str k)) (str k)))
                  lim (when-let [l (:limit args)] (int l))
                  verbose? (boolean (:verbose args))]
              (if (str/blank? intent)
                (jsonrpc-result id {:content [{:type "text" :text "Error: 'intent' is required."}]
                                    :isError true})
                (let [dedup-key [(norm-query-part intent) (norm-query-part kw) lim verbose?]
                      prev (get @seen-queries dedup-key)]
                  (if prev
                    (let [dup-text (str "DUPLICATE_QUERY: Already returned ("
                                        (str/join ", " (:results-keys prev))
                                        (when (seq (:not-installed prev))
                                          (str "; NOT_INSTALLED: " (str/join ", " (:not-installed prev))))
                                        "). Reuse above flags.")]
                      (jsonrpc-result id {:content [{:type "text" :text dup-text}]
                                          :isError false}))
                    (let [opts {:intent intent :keyword kw :limit lim :verbose? verbose?}
                          yaml-text (ubuntu/discover-yaml opts)
                          meta-keys (when-let [m (re-find #"(?s)RESULTS:\s*\n(.*)" yaml-text)]
                                      (map second (re-seq #"(?m)^  ([a-zA-Z0-9_.-]+):" (second m))))]
                      (swap! seen-queries assoc dedup-key
                             {:results-keys (vec meta-keys)
                              :not-installed (when-let [ni (re-find #"(?m)^NOT_INSTALLED:\s*\[([^\]]+)\]" yaml-text)]
                                               [(str/trim (second ni))])})
                      (jsonrpc-result id {:content [{:type "text" :text yaml-text}]
                                          :isError false}))))))
            (catch Exception e
              (jsonrpc-result id {:content [{:type "text" :text (str "Discovery error: " (.getMessage e))}]
                                  :isError true})))

          (= tool-name "check_local_command")
          (try
            (let [cmd (str (or (:command args) ""))
                  strict-preview? (if (contains? args :require_dry_run_preview)
                                    (boolean (:require_dry_run_preview args))
                                    true)
                  block-pkgs? (if (contains? args :block_package_installs)
                                (boolean (:block_package_installs args))
                                true)
                  enrich? (if (contains? args :enrich)
                            (boolean (:enrich args))
                            true)]
              (if (str/blank? cmd)
                (jsonrpc-result id {:content [{:type "text" :text "Error: 'command' is required."}]
                                    :isError true})
                (let [res (check/check-command cmd {:session-state session-state
                                                    :strict-preview? strict-preview?
                                                    :block-pkg-installs? block-pkgs?
                                                    :enrich? enrich?})]
                  (jsonrpc-result id {:content [{:type "text" :text (check/format-check-text res)}]
                                      :isError false}))))
            (catch Exception e
              (jsonrpc-result id {:content [{:type "text" :text (str "Check error: " (.getMessage e))}]
                                  :isError true})))

          (= tool-name "preflight_prompt")
          (try
            (let [prompt (str (or (:prompt args) ""))
                  hint (ubuntu/preflight prompt)]
              (jsonrpc-result id {:content [{:type "text" :text hint}]
                                  :isError false}))
            (catch Exception e
              (jsonrpc-result id {:content [{:type "text" :text (str "Preflight error: " (.getMessage e))}]
                                  :isError true})))

          :else
          (jsonrpc-result id {:content [{:type "text" :text (str "Unknown tool: " tool-name)}]
                              :isError true})))

      :else
      (jsonrpc-error id -32601 (str "Method not found: " method)))))

(defn serve-stdio!
  "Run persistent stdio JSON-RPC 2.0 MCP server for local Ubuntu tool discovery & guardrails."
  ([] (serve-stdio! {}))
  ([{:keys [expose-check?] :or {expose-check? false}}]
   ;; Pre-warm YAMLScript strategy and PATH cache once at startup
   (strategy/load-ubuntu-strategy)
   (ubuntu/scan-path-executables)
   (let [^BufferedReader reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
         seen-queries (atom {})
         session-state (atom {:previewed #{}})]
     (loop []
       (when-let [line (.readLine reader)]
         (when-not (str/blank? line)
           (try
             (let [req (json/parse-string line true)]
               (when-let [resp (handle-request req seen-queries session-state expose-check?)]
                 (write-response! resp)))
             (catch Exception e
               (binding [*out* *err*]
                 (println "MCP parse/handle error:" (.getMessage e)))
               (write-response! (jsonrpc-error nil -32700 "Parse error")))))
         (recur))))))
