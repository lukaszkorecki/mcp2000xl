(ns mcp2000xl.tool-test
  (:require [clojure.test :refer [deftest is]]
            [mcp2000xl.impl.tool :as impl.tool]
            [mcp2000xl.impl.resource :as impl.resource])
  (:import (io.modelcontextprotocol.server McpServerFeatures$SyncToolSpecification
                                           McpServerFeatures$SyncResourceSpecification
                                           McpStatelessServerFeatures$SyncToolSpecification
                                           McpStatelessServerFeatures$SyncResourceSpecification)))

(deftest can-build-session-based-tools
  (is (instance?
       McpServerFeatures$SyncToolSpecification
       (impl.tool/build-session-based
        {:name "add"
         :title "Add two numbers"
         :description "Adds two numbers together"
         :input-schema [:map [:a int?] [:b int?]]
         :output-schema [:map [:result int?]]
         :handler (fn [{:keys [a b]}]
                    {:result (+ a b)})}))))

(deftest can-build-stateless-tools
  (is (instance?
       McpStatelessServerFeatures$SyncToolSpecification
       (impl.tool/build-stateless
        {:name "add"
         :title "Add two numbers"
         :description "Adds two numbers together"
         :input-schema [:map [:a int?] [:b int?]]
         :output-schema [:map [:result int?]]
         :handler (fn [{:keys [a b]}]
                    {:result (+ a b)})}))))

(deftest can-build-session-based-resources
  (is (instance?
       McpServerFeatures$SyncResourceSpecification
       (impl.resource/build-session-based
        {:url "custom://test"
         :name "Test Resource"
         :description "A test resource"
         :mime-type "text/plain"
         :handler (fn [_request]
                    ["Test content"])}))))

(deftest can-build-stateless-resources
  (is (instance?
       McpStatelessServerFeatures$SyncResourceSpecification
       (impl.resource/build-stateless
        {:url "custom://test"
         :name "Test Resource"
         :description "A test resource"
         :mime-type "text/plain"
         :handler (fn [_request]
                    ["Test content"])}))))