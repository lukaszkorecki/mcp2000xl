(ns mcp2000xl.server
  (:require [jsonista.core :as jsonista]
            [clojure.tools.logging :as log])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServer)
           (io.modelcontextprotocol.server.transport StdioServerTransportProvider)
           (io.modelcontextprotocol.spec McpSchema$ServerCapabilities)
           (java.util List)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def mcp-mapper
  (JacksonMcpJsonMapper. jsonista/default-object-mapper))

(defn start-stdio
  "Creates and starts a STDIO MCP server. Blocks forever, handling stdin/stdout.
   
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
   - :request-timeout - Request timeout Duration (default: 30 minutes)
   
   Returns: Never (blocks forever)
   
   Example:
   (start-stdio {:name \"my-server\" 
                 :version \"1.0.0\" 
                 :tools [add-tool subtract-tool]})"
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
         request-timeout (Duration/ofMinutes 30)}}]

  (when-not (and name version)
    (throw (IllegalArgumentException. "Both :name and :version are required")))

  (log/info "Starting STDIO MCP server:" name "version" version)
  (log/info "Registered" (count tools) "tools," (count resources) "resources")

  (let [transport-provider (StdioServerTransportProvider. mcp-mapper)
        _server (.build
                 (doto (McpServer/sync transport-provider)
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

    (log/info "STDIO MCP server started successfully")
    (log/info "Reading from stdin, writing to stdout...")

    ;; Block forever - the transport handles everything
    ;; We just need to keep the process alive
    @(promise)))
