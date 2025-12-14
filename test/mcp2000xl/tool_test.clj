(ns mcp2000xl.tool-test
  (:require [clojure.test :refer [deftest is]]
            [mcp2000xl.tool :as tool])
  (:import (io.modelcontextprotocol.server McpServerFeatures$SyncToolSpecification)))

(deftest can-build-tool-specs
  (is (instance?
       McpServerFeatures$SyncToolSpecification
       (tool/create-tool-specification
        {:name "add"
         :title "Add two numbers"
         :description "Adds two numbers together"
         :input-schema [:map [:a int?] [:b int?]]
         :output-schema [:map [:result int?]]
         :handler (fn [_exchange {:keys [a b]}]
                    {:result (+ a b)})}))))