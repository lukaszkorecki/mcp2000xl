(ns mcp2000xl.schema
  "Schema validation for MCP tool and resource definitions using Malli."
  (:require [malli.core :as m]
            [malli.error :as me]))

(set! *warn-on-reflection* true)

;; Tool definition schema
(def Tool
  [:map {:closed true}
   [:name string?]
   [:handler fn?]
   [:input-schema :any] ; Will be validated as Malli schema separately
   [:output-schema :any] ; Will be validated as Malli schema separately
   [:title {:optional true} string?]
   [:description {:optional true} string?]
   [:read-only-hint {:optional true} boolean?]
   [:destructive-hint {:optional true} boolean?]
   [:idempotent-hint {:optional true} boolean?]
   [:open-world-hint {:optional true} boolean?]
   [:return-direct {:optional true} boolean?]
   [:meta {:optional true} map?]])

;; Resource definition schema
(def Resource
  [:map {:closed true}
   [:url string?]
   [:name string?]
   [:mime-type string?]
   [:handler fn?]
   [:description {:optional true} string?]])

(defn- validate-malli-schema
  "Validate that a value is a valid Malli schema"
  [schema context]
  (try
    (m/schema schema)
    true
    (catch Exception e
      (throw (ex-info (str context " must be a valid Malli schema")
                      {:schema schema
                       :error (ex-message e)}
                      e)))))

(defn validate-tool
  "Validate a tool definition map using Malli schema.
   
   Throws ex-info with detailed validation errors if invalid."
  [tool-def]
  (if-let [explanation (m/explain Tool tool-def)]
    (throw (ex-info "Invalid tool definition"
                    {:errors (me/humanize explanation)
                     :tool-def tool-def}))
    (do
      ;; Also validate that input/output schemas are valid Malli schemas
      (validate-malli-schema (:input-schema tool-def) "Tool :input-schema")
      (validate-malli-schema (:output-schema tool-def) "Tool :output-schema")
      tool-def)))

(defn validate-resource
  "Validate a resource definition map using Malli schema.
   
   Throws ex-info with detailed validation errors if invalid."
  [resource-def]
  (if-let [explanation (m/explain Resource resource-def)]
    (throw (ex-info "Invalid resource definition"
                    {:errors (me/humanize explanation)
                     :resource-def resource-def}))
    resource-def))

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
