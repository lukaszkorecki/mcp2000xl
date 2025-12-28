(ns mcp2000xl.impl.tool
  "Internal: Build MCP tool specifications from plain data definitions."
  (:require
   [clojure.tools.logging :as log]
   [clojure.walk :as walk]
   [jsonista.core :as jsonista]
   [malli.core :as m]
   [malli.error :as me]
   [malli.json-schema :as mjs]
   [malli.transform :as mt]
   [mcp2000xl.impl.json :as json])
  (:import
   [io.modelcontextprotocol.server McpStatelessServerFeatures$SyncToolSpecification]
   [io.modelcontextprotocol.spec
    McpSchema$CallToolRequest
    McpSchema$CallToolResult
    McpSchema$Tool
    McpSchema$ToolAnnotations]
   [java.io PrintWriter StringWriter]
   [java.util.function BiFunction]))

(set! *warn-on-reflection* true)

(defn- throwable->string [^Throwable t]
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

(defn- build-tool-schema
  "Build MCP Tool schema from definition"
  [{:keys [name title description input-schema output-schema
           read-only-hint destructive-hint idempotent-hint
           open-world-hint return-direct meta]
    :or {read-only-hint false
         destructive-hint false
         idempotent-hint false
         open-world-hint false
         return-direct false
         meta {}}}]
  (.build
   (doto (McpSchema$Tool/builder)
     (.name name)
     (.title title)
     (.description description)
     (.inputSchema json/mapper (jsonista/write-value-as-string (mjs/transform input-schema)))
     (.outputSchema json/mapper (jsonista/write-value-as-string (mjs/transform output-schema)))
     (.annotations (McpSchema$ToolAnnotations. title read-only-hint destructive-hint
                                               idempotent-hint open-world-hint return-direct))
     (.meta meta))))

(defn- tool-def->handler
  "Create shared validation/execution dispatch for tool handlers"
  [{:keys [name handler input-schema output-schema]}]
  (let [request-coercer (m/decoder input-schema malli-transformer)
        request-explainer (m/explainer input-schema)
        response-coercer (m/decoder output-schema malli-transformer)
        response-explainer (m/explainer output-schema)]
    (fn [request-data]
      (let [kw-req-data (walk/keywordize-keys request-data)
            coerced-request-data (request-coercer kw-req-data)]
        (if-some [explanation (request-explainer coerced-request-data)]
          ;; Input validation failed
          (do
            (let [ex (ex-info "Invalid request for tool call."
                              {:tool name
                               :request kw-req-data
                               :explanation (me/humanize explanation)})]
              (log/error ex (ex-message ex)))
            {:error true
             :content (jsonista/write-value-as-string (me/humanize explanation))})
          ;; Input valid, call handler
          (let [response-data (handler coerced-request-data)
                coerced-response-data (response-coercer response-data)]
            (if-some [explanation (response-explainer coerced-response-data)]
              ;; Output validation failed
              (do
                (let [ex (ex-info "Invalid response from tool call."
                                  {:tool name
                                   :request coerced-request-data
                                   :response coerced-response-data
                                   :explanation (me/humanize explanation)})]
                  (log/error ex (ex-message ex)))
                {:error true
                 :content (jsonista/write-value-as-string (me/humanize explanation))})
              ;; All valid
              {:error false
               :content coerced-response-data
               :meta (meta response-data)})))))))

(defn- create-tool-result
  "Create a CallToolResult from handler dispatch result"
  [result tool-name]
  (try
    (if (:error result)
      (.build
       (doto (McpSchema$CallToolResult/builder)
         (.isError true)
         (.addTextContent (:content result))))
      (.build
       (doto (McpSchema$CallToolResult/builder)
         (.structuredContent json/mapper (jsonista/write-value-as-string (:content result)))
         (.meta (or (:meta result) {})))))
    (catch Throwable e
      (let [ex (ex-info "Exception calling tool." {:tool tool-name} e)]
        (log/error ex (ex-message ex)))
      (.build
       (doto (McpSchema$CallToolResult/builder)
         (.isError true)
         (.addTextContent (throwable->string e))
         (.meta (or (meta e) {})))))))

(defn handler->bifun
  [tool-name handler]
  (reify BiFunction
    (apply [_this _context request]
      (let [request-data (McpSchema$CallToolRequest/.arguments request)
            result (handler request-data)]
        (create-tool-result result tool-name)))))

(defn build-tool [tool-def]
  (let [tool-schema (build-tool-schema tool-def)
        handler (tool-def->handler tool-def)
        tool-name (:name tool-def)]
    (.build
     (doto (McpStatelessServerFeatures$SyncToolSpecification/builder)
       (.tool tool-schema)
       (.callHandler (handler->bifun tool-name handler))))))

(defn build-tools
  "Build tool specifications from plain data definitions.

   Parameters:
   - tool-defs: Collection of tool definition maps

   Returns: Vector of Java SDK tool specification objects

  NOTE: Resource definitions must be validated before calling this function - as per `mcp2000xl.schema`
  this happens automatically when constructing the handler
  "
  [tool-defs]
  (mapv build-tool tool-defs))
