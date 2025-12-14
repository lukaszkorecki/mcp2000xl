(ns mcp2000xl.server
  (:require [jsonista.core :as jsonista])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServer)
           (io.modelcontextprotocol.server.transport HttpServletStreamableServerTransportProvider)
           (io.modelcontextprotocol.spec McpSchema$ServerCapabilities)
           (java.util List)
           (java.time Duration)))

(set! *warn-on-reflection* true)

(def mcp-mapper
  (JacksonMcpJsonMapper. jsonista/default-object-mapper))

(defn build-mcp-server
  [{:keys [name
           version
           completions
           instructions
           tools
           resources
           resource-templates
           prompts

           request-timeout
           keep-alive-interval

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
         request-timeout (Duration/ofMinutes 30)
         keep-alive-interval (Duration/ofSeconds 15)}}]

  (let [transport-provider (.build
                            (doto (HttpServletStreamableServerTransportProvider/builder)
                              (.jsonMapper mcp-mapper)
                              (.keepAliveInterval keep-alive-interval)
                              ;; this is important because the MCP servlet verifies
                              ;; that the request URI ends with whatever value this
                              ;; is set to (defaults to /mcp). We set it to an empty
                              ;; string because (.endsWith "anything" "") is always
                              ;; true, meaning the handler doesn't care what endpoint
                              ;; the ring handler is mounted at.
                              (.mcpEndpoint "")))
        server (.build
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

    {:transport-provider transport-provider
     :mcp-server server}))
