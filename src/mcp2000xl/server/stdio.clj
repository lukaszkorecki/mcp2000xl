(ns mcp2000xl.server.stdio
  "STDIO transport for MCP servers.
   Reads JSON-RPC from stdin, processes with MCP handler, writes to stdout.

   Perfect for Claude Desktop integration and process-based MCP servers."
  (:require [mcp2000xl.handler :as handler]
            [jsonista.core :as json]
            [clojure.tools.logging :as log])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]))

(set! *warn-on-reflection* true)

(defn process-line!
  "Process a single JSON-RPC request line.

   Parameters:
   - handler - Handler created with mcp2000xl.handler/create-handler
   - line - JSON-RPC request as string

   Returns: JSON-RPC response as string, or nil for notifications"
  [handler line]
  (try
    (let [request (json/read-value line json/keyword-keys-object-mapper)]
      ;; Notifications (no ID) are ignored
      (if-not (:id request)
        (do
          (log/debug "Ignoring notification" {:method (:method request)})
          nil)
        (json/write-value-as-string (handler/invoke handler request))))
    (catch Exception e
      (log/error e "Error processing request" {:line line})
      (json/write-value-as-string
       {:jsonrpc "2.0"
        :id (try
              (-> line (json/read-value json/keyword-keys-object-mapper) :id)
              (catch Exception _ nil))
        :error {:code -32700
                :message "Parse error"
                :data {:exception (ex-message e)}}}))))

(defn create
  "Creates and starts a STDIO MCP server. Blocks forever processing requests.

   Reads JSON-RPC requests from stdin (one per line), processes them with an MCP
   handler, and writes responses to stdout (one per line).

   Tools and resources are plain Clojure maps (see mcp2000xl.schema for validation).

   Options (same as mcp2000xl.handler/create-handler):
   - :name (required) - Server name
   - :version (required) - Server version
   - :tools - Vector of tool definition maps (default: [])
   - :resources - Vector of resource definition maps (default: [])
   - :prompts - Vector of prompt specifications (default: [])
   - :resource-templates - Vector of resource templates (default: [])
   - :completions - Vector of completion specifications (default: [])
   - :instructions - Instructions for the AI (default: 'Call these tools to assist the user.')
   - :logging - Enable logging (default: false)
   - :experimental - Experimental features map (default: {})
   - :request-timeout - Request timeout Duration (default: 10 seconds)

   Returns: Never (blocks forever)

   Example:
   (create {:name \"my-server\"
            :version \"1.0.0\"
            :tools [{:name \"add\"
                     :description \"Adds two numbers\"
                     :input-schema [:map [:a int?] [:b int?]]
                     :output-schema [:map [:result int?]]
                     :handler (fn [{:keys [a b]}] {:result (+ a b)})}]})"
  [opts]
  (log/info "Creating MCP handler")
  (let [handler (handler/create opts)]
    (log/info "Starting STDIO server - reading from stdin, writing to stdout")

    (with-open [reader (BufferedReader. (InputStreamReader. System/in "UTF-8"))
                writer (BufferedWriter. (OutputStreamWriter. System/out "UTF-8"))]
      (loop []
        (when-let [line (.readLine reader)]
          (when-let [response (process-line! handler line)]
            (.write ^BufferedWriter writer ^String response)
            (.newLine ^BufferedWriter writer)
            (.flush ^BufferedWriter writer))
          (recur))))))
