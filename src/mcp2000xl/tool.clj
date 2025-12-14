(ns mcp2000xl.tool
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as jsonista]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServerFeatures$SyncToolSpecification)
           (io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$CallToolResult McpSchema$Tool McpSchema$ToolAnnotations)
           (java.io PrintWriter StringWriter)
           (java.util.function BiFunction)))

(set! *warn-on-reflection* true)

(def mcp-mapper
  (JacksonMcpJsonMapper. jsonista/default-object-mapper))

(defn throwable->string [t]
  (let [sw (StringWriter.)]
    (with-open [sw sw
                pw (PrintWriter. sw)]
      (Throwable/.printStackTrace t pw))
    (StringWriter/.toString sw)))

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
              (let [request-data (McpSchema$CallToolRequest/.arguments request)
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
