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
   :description "Discover verified local Ubuntu CLI tools, dpkg package provenance, side_effects/usage_order safety metadata, and exact flags from man/--help without guessing or installing."
   :inputSchema {:type "object"
                 :properties {:intent {:type "string"
                                       :description "Short description of what you want to do (e.g. 'extract PNG frame from video', 'compute 256-bit blake2 checksum')."}
                              :keyword {:type "string"
                                        :description "Optional command name or topic keyword to check/prioritize (e.g. 'rg', 'fd', 'b2sum', 'pdf')."}
                              :limit {:type "integer"
                                      :description "Max verified tools to return (1-5; defaults to 1 for exact binary match, 2 for intent search)."}}
                 :required ["intent"]}})

(def ^:private check-tool-definition
  {:name "check_local_command"
   :description "Deterministic PreToolUse guardrail that validates a proposed bash command against local binaries, documented long flags, GNU tar/redirect footguns, and dry-run preview requirements before execution."
   :inputSchema {:type "object"
                 :properties {:command {:type "string"
                                        :description "Proposed shell command string to validate."}
                              :require_dry_run_preview {:type "boolean"
                                                        :description "Require a --dry-run (-n) preview in this session before destructive rsync --delete (default true)."}
                              :block_package_installs {:type "boolean"
                                                       :description "Block apt/dpkg/pip/snap install commands (default true)."}}
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

(defn- handle-request [req seen-queries session-state]
  (let [id (:id req)
        method (:method req)
        params (:params req)]
    (cond
      (= method "initialize")
      (do (reset! seen-queries {})
          (reset! session-state {:previewed #{}})
          (jsonrpc-result id {:protocolVersion "2024-11-05"
                              :capabilities {:tools {}}
                              :serverInfo {:name "mandy" :version "0.3.0-ubuntu"}}))

      (= method "notifications/mandy/reset_session")
      (do (reset! seen-queries {})
          (reset! session-state {:previewed #{}})
          nil)

      (or (nil? id) (= method "notifications/initialized"))
      nil

      (= method "ping")
      (jsonrpc-result id {})

      (= method "tools/list")
      (jsonrpc-result id {:tools [discover-tool-definition check-tool-definition]})

      (= method "tools/call")
      (let [tool-name (:name params)
            args (:arguments params)]
        (cond
          (= tool-name "discover_local_tool")
          (try
            (let [intent (str (or (:intent args) ""))
                  kw (when-let [k (:keyword args)]
                       (when-not (str/blank? (str k)) (str k)))
                  lim (when-let [l (:limit args)] (int l))]
              (if (str/blank? intent)
                (jsonrpc-result id {:content [{:type "text" :text "Error: 'intent' is required."}]
                                    :isError true})
                (let [dedup-key [(norm-query-part intent) (norm-query-part kw) lim]
                      prev (get @seen-queries dedup-key)]
                  (if prev
                    (let [dup-text (str "DUPLICATE_QUERY: Already returned above (RESULTS: ["
                                        (str/join ", " (:results-keys prev))
                                        "]; NOT_INSTALLED: ["
                                        (str/join ", " (:not-installed prev))
                                        "]). Use the previously returned flags/binaries directly without re-querying.")]
                      (jsonrpc-result id {:content [{:type "text" :text dup-text}]
                                          :isError false}))
                    (let [res (ubuntu/discover {:intent intent :keyword kw :limit lim})
                          yaml-text (ubuntu/format-discovery-yaml res)]
                      (swap! seen-queries assoc dedup-key
                             {:results-keys (mapv name (keys (:RESULTS res)))
                              :not-installed (vec (:NOT_INSTALLED res))})
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
                                true)]
              (if (str/blank? cmd)
                (jsonrpc-result id {:content [{:type "text" :text "Error: 'command' is required."}]
                                    :isError true})
                (let [res (check/check-command cmd {:session-state session-state
                                                    :strict-preview? strict-preview?
                                                    :block-pkg-installs? block-pkgs?})]
                  (jsonrpc-result id {:content [{:type "text" :text (check/format-check-text res)}]
                                      :isError false}))))
            (catch Exception e
              (jsonrpc-result id {:content [{:type "text" :text (str "Check error: " (.getMessage e))}]
                                  :isError true})))

          :else
          (jsonrpc-result id {:content [{:type "text" :text (str "Unknown tool: " tool-name)}]
                              :isError true})))

      :else
      (jsonrpc-error id -32601 (str "Method not found: " method)))))

(defn serve-stdio!
  "Run persistent stdio JSON-RPC 2.0 MCP server for local Ubuntu tool discovery."
  []
  ;; Pre-warm YAMLScript strategy and dpkg metadata once at startup
  (strategy/load-ubuntu-strategy)
  (ubuntu/load-dpkg-status)
  (let [reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
        seen-queries (atom {})
        session-state (atom {:previewed #{}})]
    (loop []
      (when-let [line (.readLine reader)]
        (when-not (str/blank? line)
          (try
            (let [req (json/parse-string line true)]
              (when-let [resp (handle-request req seen-queries session-state)]
                (write-response! resp)))
            (catch Exception e
              (binding [*out* *err*]
                (println "MCP parse/handle error:" (.getMessage e)))
              (write-response! (jsonrpc-error nil -32700 "Parse error")))))
        (recur)))))
