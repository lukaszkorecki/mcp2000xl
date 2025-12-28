(ns mcp2000xl.impl.resource
  "Internal: Build MCP resource specifications from plain data definitions."
  (:require [clojure.tools.logging :as log])
  (:import [io.modelcontextprotocol.server McpStatelessServerFeatures$SyncResourceSpecification]
           [io.modelcontextprotocol.spec McpSchema$Resource
            McpSchema$TextResourceContents
            McpSchema$ReadResourceResult]
           [java.util.function BiFunction]))

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
  "Create the BiFunction handler for resource operations"
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

(defn build-resource [resource-def]
  (McpStatelessServerFeatures$SyncResourceSpecification.
   (build-resource-schema resource-def)
   (create-resource-handler resource-def)))

(defn build-resources
  "Build resource specifications from plain data definitions.

   Parameters:
   - resource-defs: Collection of resource definition maps

   Returns: Vector of Java SDK resource specification objects
  NOTE: Resource definitions must be validated before calling this function - as per `mcp2000xl.schema`
  this happens automatically when constructing the handler
  "
  [resource-defs]
  (mapv build-resource resource-defs))
