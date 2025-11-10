(ns com.latacora.mcp.core
  (:require [clojure.tools.logging :as log]
            [jsonista.core :as jsonista]
            [malli.core :as m]
            [malli.error :as me]
            [malli.json-schema :as mjs]
            [malli.transform :as mt]
            [ring.adapter.jetty :as jetty]
            [ring.util.jakarta.servlet :as servlet]
            [ring.websocket :as ws])
  (:import (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)
           (io.modelcontextprotocol.server McpServer McpServerFeatures$SyncToolSpecification)
           (io.modelcontextprotocol.server.transport HttpServletStreamableServerTransportProvider)
           (io.modelcontextprotocol.spec McpSchema$CallToolRequest McpSchema$CallToolResult McpSchema$ServerCapabilities McpSchema$Tool McpSchema$ToolAnnotations)
           (jakarta.servlet.http HttpServlet)
           (java.io PrintWriter StringWriter)
           (jakarta.servlet.http HttpServletRequest)
           (java.util List)
           (java.io InputStream)
           (java.time Duration)
           (java.util Locale)
           (java.util.function BiFunction)
           (org.eclipse.jetty.ee9.nested Request)
           (org.eclipse.jetty.ee9.servlet ServletContextHandler ServletHandler)
           (org.eclipse.jetty.server Server)))


(defn lazy-input-stream [delayed-stream]
  (proxy [InputStream] []
    (read
      ([] (.read ^InputStream @delayed-stream))
      ([b] (.read ^InputStream @delayed-stream b))
      ([b off len] (.read ^InputStream @delayed-stream b off len)))
    (available [] (.available ^InputStream @delayed-stream))
    (close [] (.close ^InputStream @delayed-stream))
    (mark [readlimit] (.mark ^InputStream @delayed-stream readlimit))
    (markSupported [] (.markSupported ^InputStream @delayed-stream))
    (reset [] (.reset ^InputStream @delayed-stream))
    (skip [n] (.skip ^InputStream @delayed-stream n))))

(defn build-request-map
  "Create the request map from the HttpServletRequest object."
  [^HttpServletRequest request]
  {:server-port        (.getServerPort request)
   :server-name        (.getServerName request)
   :remote-addr        (.getRemoteAddr request)
   :uri                (.getRequestURI request)
   :query-string       (.getQueryString request)
   :scheme             (keyword (.getScheme request))
   :request-method     (keyword (.toLowerCase (.getMethod request) Locale/ENGLISH))
   :protocol           (.getProtocol request)
   :headers            (#'servlet/get-headers request)
   :content-type       (.getContentType request)
   :content-length     (#'servlet/get-content-length request)
   :character-encoding (.getCharacterEncoding request)
   :ssl-client-cert    (#'servlet/get-client-cert request)
   :body               (lazy-input-stream (fn [] (.getInputStream request)))})


(defn proxy-handler [handler options]
  (proxy [ServletHandler] []
    (doHandle [_ ^Request base-request request response]
      (let [request-map  (build-request-map request)
            response-map (handler (with-meta request-map {:request request :response response}))]
        (when-not (.isHandled request)
          (try
            (if (ws/websocket-response? response-map)
              (#'jetty/upgrade-to-websocket request response response-map options)
              (servlet/update-servlet-response response response-map))
            (finally
              (-> response .getOutputStream .close)
              (.setHandled base-request true))))))))

(defn run-jetty
  "Like ring.adapter.jetty/run-jetty but passes the raw request and response along for MCP usage."
  ^Server [handler options]
  (let [server (#'jetty/create-server (dissoc options :configurator))
        proxy  (proxy-handler handler options)]
    (.setHandler ^Server server ^ServletContextHandler (#'jetty/context-handler proxy))
    (when-let [configurator (:configurator options)]
      (configurator server))
    (try
      (.start server)
      (when (:join? options true)
        (.join server))
      server
      (catch Exception ex
        (.stop ^Server server)
        (throw ex)))))


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
       ::mt/json-vectors        true})
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
    :or   {read-only-hint   false
           destructive-hint false
           idempotent-hint  false
           open-world-hint  false
           return-direct    false
           meta             {}}}]
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
        (let [request-coercer    (m/decoder input-schema malli-transformer)
              request-explainer  (m/explainer input-schema)
              response-coercer   (m/decoder output-schema malli-transformer)
              response-explainer (m/explainer output-schema)]
          (reify BiFunction
            (apply [this exchange request]
              (try
                (let [request-data         (.arguments ^McpSchema$CallToolRequest request)
                      ; hack to convert java datastructures into clojure ones.
                      ; this could be done more efficiently with a protocol
                      ; instead of serialization roundtrip
                      clojure-request-data (jsonista/read-value (jsonista/write-value-as-string request-data))
                      coerced-request-data (request-coercer clojure-request-data)]
                  (if-some [explanation (request-explainer coerced-request-data)]
                    (do
                      (let [ex (ex-info "Invalid request for tool call."
                                        {:tool        name
                                         :request     clojure-request-data
                                         :explanation (me/humanize explanation)})]
                        (log/error ex (ex-message ex)))
                      (.build
                        (doto (McpSchema$CallToolResult/builder)
                          (.isError true)
                          (.addTextContent (jsonista/write-value-as-string (me/humanize explanation))))))
                    (let [response-data         (handler exchange coerced-request-data)
                          coerced-response-data (response-coercer response-data)]
                      (if-some [explanation (response-explainer coerced-response-data)]
                        (do
                          (let [ex (ex-info "Invalid response from tool call."
                                            {:tool        name
                                             :request     coerced-request-data
                                             :response    coerced-response-data
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


(defn create-ring-handler
  "Creates a ring handler that functions as a complete streamable-http MCP endpoint. This is
   intended to be more convenient to incorporate into Clojure servers than the underlying Java
   library would otherwise support. It achieves this through cooperation with a custom jetty
   adapter that allows individual ring handlers to assume complete responsibility for the
   request/response lifecycle and therefore must be used with com.latacora.mcp.core/run-jetty
   when starting your jetty server. The behavior is otherwise identical to ring.adapter.jetty/run-jetty."
  [{:keys [name
           version
           tools
           prompts
           resources
           resource-templates
           completions
           experimental
           logging
           instructions
           request-timeout
           keep-alive-interval]
    :or   {tools               []
           prompts             []
           resources           []
           resource-templates  []
           experimental        {}
           completions         []
           logging             true
           instructions        "Call these tools to assist the user."
           request-timeout     (Duration/ofMinutes 30)
           keep-alive-interval (Duration/ofSeconds 15)}}]
  (let [tp-provider
        (.build
          (doto (HttpServletStreamableServerTransportProvider/builder)
            (.jsonMapper mcp-mapper)
            (.keepAliveInterval keep-alive-interval)
            ; this is important because the MCP servlet verifies
            ; that the request URI ends with whatever value this
            ; is set to (defaults to /mcp). We set it to an empty
            ; string because (.endsWith "anything" "") is always
            ; true, meaning the handler doesn't care what endpoint
            ; the ring handler is mounted at.
            (.mcpEndpoint "")))
        server
        (.build
          (doto (McpServer/sync tp-provider)
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
    (fn handler
      ([request]
       (let [{:keys [request response]} (meta request)]
         (try
           (.service ^HttpServlet tp-provider request response)
           (finally (.setHandled request true)))))
      ([request respond raise]
       (try (respond (handler request))
            (catch Throwable e (raise e)))))))



(comment

  (def tool
    (create-tool-specification
      {:name          "add"
       :title         "Add two numbers"
       :description   "Adds two numbers together"
       :input-schema  [:map [:a int?] [:b int?]]
       :output-schema [:map [:result int?]]
       :handler       (fn [_exchange {:keys [a b]}]
                        {:result (+ a b)})}))

  (def handler (create-ring-handler {:name "hello-world" :version "1.0.0" :tools [tool]}))

  (def server (run-jetty handler {:port 3000 :join? false}))

  )
