(ns mcp2000xl.schema
  "Schema validation for MCP tool and resource definitions.
   
   Tools and resources are defined as plain Clojure maps, validated using Malli."
  (:require [malli.core :as m]))

(defn validate-tool
  "Validate a tool definition map.
   
   Required keys:
   - :name - Tool name (string)
   - :handler - Function (fn [args] result)
   - :input-schema - Malli schema for input
   - :output-schema - Malli schema for output
   
   Optional keys:
   - :title - Tool title
   - :description - Tool description
   - :read-only-hint - Boolean (default: false)
   - :destructive-hint - Boolean (default: false)
   - :idempotent-hint - Boolean (default: false)
   - :open-world-hint - Boolean (default: false)
   - :return-direct - Boolean (default: false)
   - :meta - Metadata map (default: {})
   
   Throws IllegalArgumentException if invalid."
  [{:keys [name handler input-schema output-schema] :as tool-def}]
  (when-not name
    (throw (IllegalArgumentException. "Tool :name is required")))
  (when-not handler
    (throw (IllegalArgumentException. "Tool :handler is required")))
  (when-not (fn? handler)
    (throw (IllegalArgumentException. "Tool :handler must be a function")))
  (when-not input-schema
    (throw (IllegalArgumentException. "Tool :input-schema is required")))
  (when-not output-schema
    (throw (IllegalArgumentException. "Tool :output-schema is required")))
  ;; Try to compile schemas to validate they're correct
  (try
    (m/schema input-schema)
    (catch Exception e
      (throw (IllegalArgumentException.
              (str "Tool :input-schema must be a valid Malli schema: " (ex-message e))))))
  (try
    (m/schema output-schema)
    (catch Exception e
      (throw (IllegalArgumentException.
              (str "Tool :output-schema must be a valid Malli schema: " (ex-message e))))))
  tool-def)

(defn validate-resource
  "Validate a resource definition map.
   
   Required keys:
   - :url - Resource URL/URI (string)
   - :name - Resource name (string)
   - :mime-type - MIME type (string, e.g., \"text/plain\")
   - :handler - Function (fn [request] result-strings)
   
   Optional keys:
   - :description - Resource description
   
   Throws IllegalArgumentException if invalid."
  [{:keys [url name mime-type handler] :as resource-def}]
  (when-not url
    (throw (IllegalArgumentException. "Resource :url is required")))
  (when-not name
    (throw (IllegalArgumentException. "Resource :name is required")))
  (when-not mime-type
    (throw (IllegalArgumentException. "Resource :mime-type is required")))
  (when-not handler
    (throw (IllegalArgumentException. "Resource :handler is required")))
  (when-not (fn? handler)
    (throw (IllegalArgumentException. "Resource :handler must be a function")))
  resource-def)

(defn validate-tools
  "Validate a collection of tool definitions."
  [tool-defs]
  (doseq [tool-def tool-defs]
    (validate-tool tool-def))
  tool-defs)

(defn validate-resources
  "Validate a collection of resource definitions."
  [resource-defs]
  (doseq [resource-def resource-defs]
    (validate-resource resource-def))
  resource-defs)
