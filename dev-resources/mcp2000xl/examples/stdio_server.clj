(ns mcp2000xl.examples.stdio-server
  "Example STDIO MCP server with a simple add tool"
  (:require [mcp2000xl.server :as server]
            [mcp2000xl.tool :as tool]))

(def add-tool
  (tool/create-tool-specification
   {:name "add"
    :title "Add Two Numbers"
    :description "Adds two numbers together and returns the result"
    :input-schema [:map
                   [:a {:description "First number"} int?]
                   [:b {:description "Second number"} int?]]
    :output-schema [:map
                    [:result {:description "Sum of a and b"} int?]]
    :handler (fn [_exchange {:keys [a b]}]
               {:result (+ a b)})}))

(defn -main [& _args]
  (server/start-stdio
   {:name "example-stdio-server"
    :version "1.0.0"
    :instructions "This is an example MCP server. Use the 'add' tool to add two numbers."
    :tools [add-tool]}))
