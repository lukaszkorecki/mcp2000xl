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

(defn- create-resource-handler
  "Create the BiFunction handler - same logic for both session-based and stateless"
  [{:keys [url mime-type handler]}]
  (reify BiFunction
    (apply [_this _context-or-exchange request]
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
            (McpSchema$ReadResourceResult. [error-content])))))))

(defmulti build-resource
  "Build a resource specification based on server type"
  (fn [_resource-def server-type] server-type))

(defmethod build-resource :session-based
  [resource-def _]
  (McpServerFeatures$SyncResourceSpecification.
   (build-resource-schema resource-def)
   (create-resource-handler resource-def)))

(defmethod build-resource :stateless
  [resource-def _]
  (McpStatelessServerFeatures$SyncResourceSpecification.
   (build-resource-schema resource-def)
   (create-resource-handler resource-def)))

(defn build-resources
  "Build resource specifications from plain data definitions.
   
   Parameters:
   - resource-defs: Collection of resource definition maps
   - server-type: :session-based or :stateless
   
   Returns: Vector of Java SDK resource specification objects"
  [resource-defs server-type]
  (mapv #(build-resource % server-type) resource-defs))
