(ns mcp2000xl.handler
  "MCP request handler for building MCP servers"
  (:require [clojure.tools.logging :as log]
            [clojure.walk :as walk]
            [jsonista.core :as json]
            [mcp2000xl.schema :as schema]
            [mcp2000xl.impl.tool :as impl.tool]
            [mcp2000xl.impl.resource :as impl.resource])
  (:import [io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper]
           [io.modelcontextprotocol.server McpServer McpStatelessServerHandler McpServer$StatelessSyncSpecification]
           [io.modelcontextprotocol.spec McpStatelessServerTransport McpSchema$ServerCapabilities McpSchema$JSONRPCRequest McpSchema$JSONRPCResponse]
           [io.modelcontextprotocol.common McpTransportContext]
           [reactor.core.publisher Mono]
           [java.util List]
           [java.time Duration]))

(set! *warn-on-reflection* true)

(def mcp-mapper
  (JacksonMcpJsonMapper. json/default-object-mapper))

(defrecord HandlerCapturingTransport [handler-atom]
  McpStatelessServerTransport
  (setMcpHandler [_ handler]
    (reset! handler-atom handler))
  (closeGracefully [_]
    (Mono/empty))
  (close [_]))

(defn create
  "Creates an MCP handler for processing requests.
   Returns a handler that can be passed to invoke or used with mcp2000xl.server.stdio.

   Tools and resources are plain Clojure maps (see mcp2000xl.schema for validation).

   Options:
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

   Returns: Handler object that can be passed to invoke

   Example:
   (def handler (create
                  {:name \"my-server\"
                   :version \"1.0.0\"
                   :tools [{:name \"add\"
                            :description \"Adds two numbers\"
                            :input-schema [:map [:a int?] [:b int?]]
                            :output-schema [:map [:result int?]]
                            :handler (fn [{:keys [a b]}] {:result (+ a b)})}]}))"
  [{:keys [name
           version
           completions
           instructions
           tools
           resources
           resource-templates
           prompts
           request-timeout
           experimental
           logging]
    :or {tools []
         prompts []
         resources []
         resource-templates []
         experimental {}
         completions []
         logging false
         instructions "Call these tools to assist the user."
         request-timeout (Duration/ofSeconds 10)}}]

  (when-not (and name version)
    (throw (IllegalArgumentException. "Both :name and :version are required")))

  ;; Validate tool and resource definitions
  (schema/validate-tools tools)
  (schema/validate-resources resources)

  (log/info "Creating MCP handler:" name "version" version)

  ;; Build Java SDK specifications from plain data
  (let [built-tools (impl.tool/build-tools tools)
        built-resources (impl.resource/build-resources resources)]

    (log/info "Registered" (count built-tools) "tools," (count built-resources) "resources")

    (let [handler-atom (atom nil)
          transport (->HandlerCapturingTransport handler-atom)
          builder (McpServer/sync ^McpStatelessServerTransport transport)
          ;; NOTE: this is not a 'real' server - we are not holding on to any resources etc, so it doesn't need
          ;;       explicit shutdown or anything like that
          ;; XXX: should we though? Any chances for memory leaks?
          _server (.build (doto ^McpServer$StatelessSyncSpecification builder
                            (.serverInfo name version)
                            (.jsonMapper mcp-mapper)
                            (.completions ^List completions)
                            (.instructions instructions)
                            (.tools ^List built-tools)
                            (.resources ^List built-resources)
                            (.resourceTemplates ^List resource-templates)
                            (.prompts ^List prompts)
                            (.requestTimeout request-timeout)
                            (.capabilities (.build (cond-> (McpSchema$ServerCapabilities/builder)
                                                           (not-empty experimental) (.experimental experimental)
                                                           (not-empty built-resources) (.resources true true)
                                                           (not-empty built-tools) (.tools true)
                                                           (not-empty prompts) (.prompts true)
                                                           (not-empty completions) (.completions)
                                                           logging (.logging))))))]

      (log/info "MCP handler created successfully")
      @handler-atom)))

(defn invoke
  "Invokes the MCP handler with a request as a Clojure map.

   Efficiently processes requests by passing Clojure data structures directly
   to the handler without wasteful JSON serialization/deserialization cycles.
   Keys are automatically stringified to ensure compatibility.

   Parameters:
   - handler - Handler created with create
   - request-map - JSON-RPC request as Clojure map with keys:
     - :jsonrpc (optional, defaults to \"2.0\")
     - :id (required) - Request ID (string or number)
     - :method (required) - Method name (e.g., \"tools/call\")
     - :params (optional) - Request parameters as map
   - opts (optional) - Options map:
     - :timeout-ms - Timeout in milliseconds (default: 30000)

   Returns: Clojure map with:
     - :jsonrpc - JSON-RPC version
     - :id - Request ID
     - :result - Result object (on success)
     - :error - Error object (on error)

   Example:
   (invoke handler
           {:jsonrpc \"2.0\"
            :id 1
            :method \"tools/call\"
            :params {:name \"add\" :arguments {:a 1 :b 2}}})"
  ([handler request]
   (invoke handler request {}))
  ([handler request {:keys [timeout-ms] :or {timeout-ms 30000}}]
   (try
     (let [jsonrpc-request (McpSchema$JSONRPCRequest.
                            (get request :jsonrpc "2.0")
                            (get request :method)
                            (get request :id)
                            (get request :params))

           ;; Create empty transport context
           context McpTransportContext/EMPTY
           ;; Call the handler
           response-mono (McpStatelessServerHandler/.handleRequest handler context jsonrpc-request)

           ;; Block and get response (with timeout)
           ^McpSchema$JSONRPCResponse response (-> response-mono
                                                   (.timeout (Duration/ofMillis timeout-ms))
                                                   (.block))

           ;; Extract fields to Clojure map - convert Java objects to Clojure data
           result-obj (.result response)
           error-obj (.error response)]
       ;; TODO: investigate whether we need this writeValueAsString + read-value dance or we can do it more directly?
       (cond-> {:jsonrpc (.jsonrpc response)
                :id (.id response)}
               result-obj (assoc :result
                                 (json/read-value
                                  (.writeValueAsString ^JacksonMcpJsonMapper mcp-mapper result-obj)
                                  json/keyword-keys-object-mapper))
               error-obj (assoc :error
                                (json/read-value
                                 (.writeValueAsString ^JacksonMcpJsonMapper mcp-mapper error-obj)
                                 json/keyword-keys-object-mapper))))

     (catch Exception e
       (log/error e "Error invoking MCP handler")
       {:jsonrpc "2.0"
        :id (get request :id)
        :error {:code -32603
                :message (ex-message e)
                :data {:type (-> e class .getName)}}}))))
