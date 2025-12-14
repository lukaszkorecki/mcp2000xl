(ns mcp2000xl.server.stdio
  "STDIO MCP server for session-based communication.
   
   Creates servers that communicate via stdin/stdout, perfect for
   Claude Desktop and other MCP clients that use process-based communication."
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as jsonista]
            [mcp2000xl.schema :as schema]
            [mcp2000xl.impl.tool :as impl.tool]
            [mcp2000xl.impl.resource :as impl.resource])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServer)
           (io.modelcontextprotocol.server.transport StdioServerTransportProvider)
           (io.modelcontextprotocol.spec McpSchema$ServerCapabilities)
           (java.util List)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def mcp-mapper
  (JacksonMcpJsonMapper. jsonista/default-object-mapper))

(defn create
  "Creates and starts a STDIO MCP server. Blocks forever, handling stdin/stdout.
   
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
   - :logging - Enable logging (default: true)
   - :experimental - Experimental features map (default: {})
   - :request-timeout - Request timeout Duration (default: 30 minutes)
   
   Returns: Never (blocks forever)
   
   Example:
   (create {:name \"my-server\"
            :version \"1.0.0\"
            :tools [{:name \"add\"
                     :description \"Adds two numbers\"
                     :input-schema [:map [:a int?] [:b int?]]
                     :output-schema [:map [:result int?]]
                     :handler (fn [{:keys [a b]}] {:result (+ a b)})}]})"
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

  ;; Validate tool and resource definitions
  (schema/validate-tools tools)
  (schema/validate-resources resources)

  (log/info "Starting STDIO MCP server:" name "version" version)

  ;; Build Java SDK specifications from plain data
  (let [built-tools (impl.tool/build-tools tools :session-based)
        built-resources (impl.resource/build-resources resources :session-based)]

    (log/info "Registered" (count built-tools) "tools," (count built-resources) "resources")

    (let [transport-provider (StdioServerTransportProvider. mcp-mapper)
          _server (.build
                   (doto (McpServer/sync transport-provider)
                     (.serverInfo name version)
                     (.jsonMapper mcp-mapper)
                     (.completions ^List completions)
                     (.instructions instructions)
                     (.tools ^List built-tools)
                     (.resources ^List built-resources)
                     (.resourceTemplates ^List resource-templates)
                     (.prompts ^List prompts)
                     (.requestTimeout request-timeout)
                     (.capabilities
                      (.build
                       (cond-> (McpSchema$ServerCapabilities/builder)
                               (not-empty experimental)
                               (.experimental experimental)
                               (not-empty built-resources)
                               (.resources true true)
                               (not-empty built-tools)
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
      @(promise))))
