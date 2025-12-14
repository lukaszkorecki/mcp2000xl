(ns mcp2000xl.core
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as jsonista]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServer McpServerFeatures$SyncToolSpecification McpServerFeatures$SyncResourceSpecification)
           (io.modelcontextprotocol.server.transport HttpServletStreamableServerTransportProvider StdioServerTransportProvider)
           (io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$CallToolResult McpSchema$ServerCapabilities McpSchema$Tool McpSchema$ToolAnnotations
                                         McpSchema$Resource McpSchema$TextResourceContents McpSchema$ReadResourceResult)
           (jakarta.servlet.http HttpServlet)
           (java.io PrintWriter StringWriter)
           (java.util List)
           (java.time Duration)
           (java.util.function BiFunction)))

(set! *warn-on-reflection* true)

(def mcp-mapper
  (JacksonMcpJsonMapper. jsonista/default-object-mapper))

(defn throwable->string [^Throwable t]
  (let [sw (StringWriter.)]
    (with-open [sw sw
                pw (PrintWriter. sw)]
      (.printStackTrace t pw))
    (.toString sw)))

(def malli-transformer
  (mt/transformer
   (mt/json-transformer
    {::mt/keywordize-map-keys true
     ::mt/json-vectors true})
   (mt/default-value-transformer)
   (mt/collection-transformer)))

(defn create-tool-specification
  "Create MCP tool specification from clojure data / function / malli schemas."
  [{:keys [name
           title
           handler
           description
           input-schema
           output-schema
           read-only-hint
           destructive-hint
           idempotent-hint
           open-world-hint
           return-direct
           meta]
    :or {read-only-hint false
         destructive-hint false
         idempotent-hint false
         open-world-hint false
         return-direct false
         meta {}}}]
  (.build
   (doto (McpServerFeatures$SyncToolSpecification/builder)
     (.tool
      (.build
       (doto (McpSchema$Tool/builder)
         (.name name)
         (.title title)
         (.description description)
         (.inputSchema mcp-mapper (jsonista/write-value-as-string (mjs/transform input-schema)))
         (.outputSchema mcp-mapper (jsonista/write-value-as-string (mjs/transform output-schema)))
         (.annotations (McpSchema$ToolAnnotations. title read-only-hint destructive-hint idempotent-hint open-world-hint return-direct))
         (.meta meta))))
     (.callHandler
      (let [request-coercer (m/decoder input-schema malli-transformer)
            request-explainer (m/explainer input-schema)
            response-coercer (m/decoder output-schema malli-transformer)
            response-explainer (m/explainer output-schema)]
        (reify BiFunction
          (apply [_this exchange request]
            (try
              (let [request-data (.arguments ^McpSchema$CallToolRequest request)
                    ;; hack to convert java datastructures into clojure ones.
                    ;; this could be done more efficiently with a protocol
                    ;; instead of serialization roundtrip
                    clojure-request-data (jsonista/read-value (jsonista/write-value-as-string request-data))
                    coerced-request-data (request-coercer clojure-request-data)]
                (if-some [explanation (request-explainer coerced-request-data)]
                  (do
                    (let [ex (ex-info "Invalid request for tool call."
                                      {:tool name
                                       :request clojure-request-data
                                       :explanation (me/humanize explanation)})]
                      (log/error ex (ex-message ex)))
                    (.build
                     (doto (McpSchema$CallToolResult/builder)
                       (.isError true)
                       (.addTextContent (jsonista/write-value-as-string (me/humanize explanation))))))
                  (let [response-data (handler exchange coerced-request-data)
                        coerced-response-data (response-coercer response-data)]
                    (if-some [explanation (response-explainer coerced-response-data)]
                      (do
                        (let [ex (ex-info "Invalid response from tool call."
                                          {:tool name
                                           :request coerced-request-data
                                           :response coerced-response-data
                                           :explanation (me/humanize explanation)})]
                          (log/error ex (ex-message ex)))
                        (.build
                         (doto (McpSchema$CallToolResult/builder)
                           (.isError true)
                           (.addTextContent (jsonista/write-value-as-string (me/humanize explanation))))))
                      (.build
                       (doto (McpSchema$CallToolResult/builder)
                         (.structuredContent mcp-mapper (jsonista/write-value-as-string coerced-response-data))
                         (.meta (or (meta response-data) {}))))))))
              (catch Throwable e
                (let [ex (ex-info "Exception calling tool." {:tool name :request request} e)]
                  (log/error ex (ex-message ex)))
                (.build
                 (doto (McpSchema$CallToolResult/builder)
                   (.isError true)
                   (.addTextContent (throwable->string e))
                   (.meta (or (meta e) {})))))))))))))

(defn create-resource-specification
  "Create MCP resource specification from clojure data / function.

   Takes a map with the following keys:
    : url          - The URL/URI of the resource (e.g., \"custom://my-resource\")
    :name         - The name of the resource
    :description  - A description of what the resource provides
    :mime-type    - The MIME type of the resource (e.g., \"text/plain\", \"text/markdown\")
    :handler      - Function that implements the resource retrieval logic.
                    Signature: (fn [exchange request] ...)
                      * exchange - The MCP exchange object (usually ignored)
                      * request  - The ReadResourceRequest object (usually ignored)
                      * returns  - A vector of strings (the resource content)

   Example:
   (create-resource-specification
     {:url \"custom://readme\"
      :name \"Project README\"
      :description \"The project's README file\"
      : mime-type \"text/markdown\"
      :handler (fn [_exchange _request]
                 [(slurp \"README.md\")])})"
  [{:keys [url name description mime-type handler]}]
  (let [resource (McpSchema$Resource/builder)
        _ (doto resource
            (.uri url)
            (.name name)
            (.description description)
            (.mimeType mime-type))
        resource-obj (.build resource)]

    (McpServerFeatures$SyncResourceSpecification.
     resource-obj
     (reify BiFunction
       (apply [_this exchange request]
         (try
           (let [result-strings (handler exchange request)
                 resource-contents (mapv #(McpSchema$TextResourceContents. url mime-type %)
                                         result-strings)]
             (McpSchema$ReadResourceResult. resource-contents))
           (catch Throwable e
             (let [ex (ex-info "Exception calling resource handler."
                               {:url url :name name :request request} e)]
               (log/error ex (ex-message ex)))
             ;; Return error content
             (let [error-content (McpSchema$TextResourceContents.
                                  url
                                  "text/plain"
                                  (str "Error retrieving resource: " (ex-message e)))]
               (McpSchema$ReadResourceResult. [error-content])))))))))

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
