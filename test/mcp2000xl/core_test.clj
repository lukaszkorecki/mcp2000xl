(ns mcp2000xl.core-test
  (:require [clojure.test :refer :all]
            [mcp2000xl.core :as mcp])
  (:import (io.modelcontextprotocol.server McpServerFeatures$SyncToolSpecification)))


(deftest can-build-tool-specs
  (is (instance?
        McpServerFeatures$SyncToolSpecification
        (mcp/create-tool-specification
          {:name          "add"
           :title         "Add two numbers"
           :description   "Adds two numbers together"
           :input-schema  [:map [:a int?] [:b int?]]
           :output-schema [:map [:result int?]]
           :handler       (fn [_exchange {:keys [a b]}]
                            {:result (+ a b)})}))))