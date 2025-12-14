(ns mcp2000xl.impl.resource
  "Internal: Build MCP resource specifications from plain data definitions."
  (:require [clojure.tools.logging :as log])
  (:import (io.modelcontextprotocol.server McpServerFeatures$SyncResourceSpecification
                                           McpStatelessServerFeatures$SyncResourceSpecification)
           (io.modelcontextprotocol.spec McpSchema$Resource
                                         McpSchema$TextResourceContents
                                         McpSchema$ReadResourceResult)
           (java.util.function BiFunction)))

(set! *warn-on-reflection* true)

(defn- build-resource-schema
  "Build MCP Resource schema from definition"
  [{:keys [url name description mime-type]}]
  (.build
   (doto (McpSchema$Resource/builder)
     (.uri url)
     (.name name)
     (.description description)
     (.mimeType mime-type))))

(defn build-session-based
  "Build a session-based resource specification (for STDIO servers)"
  [{:keys [url mime-type handler] :as resource-def}]
  (let [resource-schema (build-resource-schema resource-def)]
    (McpServerFeatures$SyncResourceSpecification.
     resource-schema
     (reify BiFunction
       (apply [_this _exchange request]
         (try
           (let [result-strings (handler request)
                 resource-contents (mapv #(McpSchema$TextResourceContents. url mime-type %)
                                         result-strings)]
             (McpSchema$ReadResourceResult. resource-contents))
           (catch Throwable e
             (let [ex (ex-info "Exception calling resource handler."
                               {:url url :request request} e)]
               (log/error ex (ex-message ex)))
             (let [error-content (McpSchema$TextResourceContents.
                                  url
                                  "text/plain"
                                  (str "Error retrieving resource: " (ex-message e)))]
               (McpSchema$ReadResourceResult. [error-content])))))))))

(defn build-stateless
  "Build a stateless resource specification (for HTTP servers)"
  [{:keys [url mime-type handler] :as resource-def}]
  (let [resource-schema (build-resource-schema resource-def)]
    (McpStatelessServerFeatures$SyncResourceSpecification.
     resource-schema
     (reify BiFunction
       (apply [_this _context request]
         (try
           (let [result-strings (handler request)
                 resource-contents (mapv #(McpSchema$TextResourceContents. url mime-type %)
                                         result-strings)]
             (McpSchema$ReadResourceResult. resource-contents))
           (catch Throwable e
             (let [ex (ex-info "Exception calling resource handler."
                               {:url url :request request} e)]
               (log/error ex (ex-message ex)))
             (let [error-content (McpSchema$TextResourceContents.
                                  url
                                  "text/plain"
                                  (str "Error retrieving resource: " (ex-message e)))]
               (McpSchema$ReadResourceResult. [error-content])))))))))

(defn build-resources
  "Build resource specifications from plain data definitions.
   
   Parameters:
   - resource-defs: Collection of resource definition maps
   - server-type: :session-based or :stateless
   
   Returns: Vector of Java SDK resource specification objects"
  [resource-defs server-type]
  (mapv (case server-type
          :session-based build-session-based
          :stateless build-stateless
          (throw (IllegalArgumentException.
                  (str "Unknown server-type: " server-type ". Must be :session-based or :stateless"))))
        resource-defs))
