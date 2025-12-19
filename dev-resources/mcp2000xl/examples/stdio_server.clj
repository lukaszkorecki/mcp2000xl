(ns mcp2000xl.examples.stdio-server
  "Example STDIO MCP server with a simple add tool"
  (:require [mcp2000xl.server.stdio :as server.stdio]))

(defn -main [& _args]
  (server.stdio/create
   {:name "example-stdio-server"
    :version "1.0.0"
    :instructions "This is an example MCP server. Use the 'add' tool to add two numbers."
    :tools [{:name "add"
             :title "Add two numbers"
             :description "Adds two numbers together"
             :input-schema [:map [:a int?] [:b int?]]
             :output-schema [:map [:result int?]]
             :handler (fn [{:keys [a b]}]
                        {:result (+ a b)})}]

    :resources [{:url "custom://hello"
                 :name "Hello Resource"
                 :description "A simple hello resource"
                 :mime-type "text/plain"
                 :handler (fn [_request]
                            ["Hello, World!"])}]}))
