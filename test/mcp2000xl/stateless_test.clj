(ns mcp2000xl.stateless-test
  (:require [clojure.test :refer [deftest is testing]]
            [mcp2000xl.stateless :as stateless]))

(def add-tool
  {:name "add"
   :title "Add Two Numbers"
   :description "Adds two numbers together"
   :input-schema [:map
                  [:a int?]
                  [:b int?]]
   :output-schema [:map
                   [:result int?]]
   :handler (fn [{:keys [a b]}]
              {:result (+ a b)})})

(def readme-resource
  {:url "custom://readme"
   :name "Project README"
   :description "The project's README file"
   :mime-type "text/markdown"
   :handler (fn [_request]
              ["# Test README\n\nThis is a test."])})

(deftest test-create-handler
  (testing "Can create a stateless handler"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]})]
      (is (some? handler) "Handler should be created")
      (is (instance? io.modelcontextprotocol.server.McpStatelessServerHandler handler)
          "Handler should implement McpStatelessServerHandler"))))

(deftest test-invoke-tool-call
  (testing "Can invoke a tool and get a response"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]})

          request {:jsonrpc "2.0"
                   :id 1
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 5 :b 3}}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)) "Should have jsonrpc version")
      (is (= 1 (:id response)) "Should preserve request ID")
      (is (some? (:result response)) "Should have result")
      (is (nil? (:error response)) "Should not have error"))))

(deftest test-invoke-with-keywords
  (testing "Can invoke with keyword keys (they get stringified)"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]})

          ;; Request with keyword keys
          request {:jsonrpc "2.0"
                   :id 2
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 10 :b 20}}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 2 (:id response)))
      (is (some? (:result response)))
      (is (nil? (:error response))))))

(deftest test-invoke-list-tools
  (testing "Can list available tools"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]})

          request {:jsonrpc "2.0"
                   :id 3
                   :method "tools/list"
                   :params {}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 3 (:id response)))
      (is (some? (:result response)) "Should have result")
      (is (nil? (:error response)) "Should not have error"))))

(deftest test-invoke-with-various-types
  (testing "Can handle various JSON types in params"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]})

          ;; Test with nested structures, arrays, etc
          request {:jsonrpc "2.0"
                   :id "string-id"
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 1 :b 2}
                            :metadata {:tags ["test" "demo"]
                                       :enabled true
                                       :count 42}}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= "string-id" (:id response)) "Should handle string IDs")
      (is (some? (:result response))))))

(deftest test-invoke-error-handling
  (testing "Returns error on invalid method"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]})

          request {:jsonrpc "2.0"
                   :id 999
                   :method "nonexistent/method"
                   :params {}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 999 (:id response)))
      (is (some? (:error response)) "Should have error")
      (is (nil? (:result response)) "Should not have result"))))

(deftest test-create-handler-with-resources
  (testing "Can create handler with resources"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :tools [add-tool]
                    :resources [readme-resource]})]
      (is (some? handler) "Handler should be created with resources"))))

(deftest test-invoke-list-resources
  (testing "Can list available resources"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :resources [readme-resource]})

          request {:jsonrpc "2.0"
                   :id 10
                   :method "resources/list"
                   :params {}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 10 (:id response)))
      (is (some? (:result response)) "Should have result")
      (is (nil? (:error response)) "Should not have error"))))

(deftest test-invoke-read-resource
  (testing "Can read a resource"
    (let [handler (stateless/create-handler
                   {:name "test-server"
                    :version "1.0.0"
                    :resources [readme-resource]})

          request {:jsonrpc "2.0"
                   :id 11
                   :method "resources/read"
                   :params {:uri "custom://readme"}}

          response (stateless/invoke handler request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 11 (:id response)))
      (is (some? (:result response)) "Should have result")
      (is (nil? (:error response)) "Should not have error"))))
