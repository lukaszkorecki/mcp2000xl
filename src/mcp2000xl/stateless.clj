(ns mcp2000xl.stateless
  "Stateless MCP server support for Ring and other web frameworks"
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as json])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServer McpStatelessServerHandler McpServer$StatelessSyncSpecification)
           (io.modelcontextprotocol.spec McpStatelessServerTransport McpSchema$ServerCapabilities McpSchema)
           (io.modelcontextprotocol.common McpTransportContext)
           (reactor.core.publisher Mono)
           (java.util List)
           (java.time Duration)))

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

(defn create-handler
  "Creates a stateless MCP handler for use in web applications.
   Returns a handler that can be passed to invoke.
   
   Options:
   - :name (required) - Server name
   - :version (required) - Server version
   - :tools - Vector of tool specifications (default: [])
   - :resources - Vector of resource specifications (default: [])
   - :prompts - Vector of prompt specifications (default: [])
   - :resource-templates - Vector of resource templates (default: [])
   - :completions - Vector of completion specifications (default: [])
   - :instructions - Instructions for the AI (default: 'Call these tools to assist the user.')
   - :logging - Enable logging (default: true)
   - :experimental - Experimental features map (default: {})
   - :request-timeout - Request timeout Duration (default: 10 seconds for stateless)
   
   Returns: Handler object that can be passed to invoke
   
   Example:
   (def handler (create-handler {:name \"my-server\" 
                                 :version \"1.0.0\"
                                 :tools [add-tool]}))"
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
         logging true
         instructions "Call these tools to assist the user."
         request-timeout (Duration/ofSeconds 10)}}]

  (when-not (and name version)
    (throw (IllegalArgumentException. "Both :name and :version are required")))

  (log/info "Creating stateless MCP handler:" name "version" version)
  (log/info "Registered" (count tools) "tools," (count resources) "resources")

  (let [handler-atom (atom nil)
        transport (->HandlerCapturingTransport handler-atom)
        builder (McpServer/sync transport)
        _server (.build
                 (doto ^McpServer$StatelessSyncSpecification builder
                   (.serverInfo name version)
                   (.jsonMapper mcp-mapper)
                   (.completions ^List completions)
                   (.instructions instructions)
                   (.tools ^List tools)
                   (.resources ^List resources)
                   (.resourceTemplates ^List resource-templates)
                   (.prompts ^List prompts)
                   (.requestTimeout request-timeout)
                   (.capabilities
                    (.build
                     (cond-> (McpSchema$ServerCapabilities/builder)
                             (not-empty experimental)
                             (.experimental experimental)
                             (not-empty resources)
                             (.resources true true)
                             (not-empty tools)
                             (.tools true)
                             (not-empty prompts)
                             (.prompts true)
                             (not-empty completions)
                             (.completions)
                             logging
                             (.logging))))))]

    (log/info "Stateless MCP handler created successfully")
    @handler-atom))

(defn invoke
  "Invokes the MCP handler with a request (Clojure map or JSON string).
   
   The handler processes MCP JSON-RPC requests. For now, you must provide
   properly formatted JSON-RPC requests.
   
   Parameters:
   - handler - Handler created with create-handler
   - request - JSON-RPC request as Clojure map or JSON string
   - opts (optional) - Options map:
     - :timeout-ms - Timeout in milliseconds (default: 30000)
   
   Returns: Clojure map with the response
   
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
     (let [;; Convert request to JSON string if it's a map
           json-request (if (string? request)
                          request
                          (json/write-value-as-string request))

           ;; Parse as JSON-RPC message
           jsonrpc-request (McpSchema/deserializeJsonRpcMessage mcp-mapper json-request)

           ;; Create empty transport context  
           context McpTransportContext/EMPTY

           ;; Call the handler
           response-mono (.handleRequest
                          ^McpStatelessServerHandler handler
                          context
                          jsonrpc-request)

           ;; Block and get response (with timeout)
           response (-> response-mono
                        (.timeout (Duration/ofMillis timeout-ms))
                        (.block))

           ;; Serialize response to JSON
           json-response (McpSchema/serializeJsonRpcMessage mcp-mapper response)]

       ;; Parse back to Clojure map
       (json/read-value json-response))

     (catch Exception e
       (log/error e "Error invoking MCP handler")
       {:error (ex-message e)
        :type (-> e class .getName)}))))
