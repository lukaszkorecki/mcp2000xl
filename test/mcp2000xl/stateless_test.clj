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

(def handler (stateless/create-handler
              {:name "test-server"
               :version "1.0.0"
               :tools [add-tool]
               :resources [readme-resource]}))

(deftest handler-is-valid-test
  (testing "Can create a stateless handler"
    (is (some? handler) "Handler should be created")
    (is (instance? io.modelcontextprotocol.server.McpStatelessServerHandler handler)
        "Handler should implement McpStatelessServerHandler")))

(deftest init-test
  (testing "invoke returns pure Clojure data structures, not Java objects"
    (let [initialize-request {:jsonrpc "2.0"
                              :id 1
                              :method "initialize"
                              :params {:protocolVersion "2024-11-05"
                                       :capabilities {}
                                       :clientInfo {:name "test-client"
                                                    :version "1.0.0"}}}

          init-response (stateless/invoke handler initialize-request)]

      (is (= {:id 1
              :jsonrpc "2.0"
              :result {:capabilities {:resources {:listChanged true, :subscribe true}
                                      :tools {:listChanged true}}
                       :instructions "Call these tools to assist the user."
                       :protocolVersion "2025-06-18"
                       :serverInfo {:name "test-server", :version "1.0.0"}}}
             init-response)))))

(deftest invoke-list-tools-test
  (testing "Can list available tools"
    (let [request {:jsonrpc "2.0"
                   :id 3
                   :method "tools/list"
                   :params {}}

          response (stateless/invoke handler request)]

      (is (= {:id 3
              :jsonrpc "2.0"
              :result {:tools [{:_meta {}
                                :annotations {:destructiveHint false
                                              :idempotentHint false
                                              :openWorldHint false
                                              :readOnlyHint false
                                              :returnDirect false
                                              :title "Add Two Numbers"}
                                :description "Adds two numbers together"
                                :inputSchema {:properties {:a {:type "integer"}, :b {:type "integer"}}
                                              :required ["a" "b"]
                                              :type "object"}
                                :name "add"
                                :outputSchema {:properties {:result {:type "integer"}}
                                               :required ["result"]
                                               :type "object"}
                                :title "Add Two Numbers"}]}}
             response)))))

(deftest invoke-tool-call-test
  (testing "Can invoke a tool and get a response"
    (let [request {:jsonrpc "2.0"
                   :id 1
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 5 :b 3}}}

          response (stateless/invoke handler request)]

      (is (= {:id 1
              :jsonrpc "2.0"
              :result {:content [{:text "{\"result\":8}", :type "text"}]
                       :isError false
                       :structuredContent {:result 8}}}
             response)))))

(deftest test-invoke-with-various-types
  (testing "Can handle various JSON types in params"
    (let [;; Test with nested structures, arrays, etc
          request {:jsonrpc "2.0"
                   :id "string-id"
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 1 :b 2}
                            :metadata {:tags ["test" "demo"]
                                       :enabled true
                                       :count 42}}}

          response (stateless/invoke handler request)]

      (is (= {:id "string-id"
              :jsonrpc "2.0"
              :result {:content [{:text "{\"result\":3}", :type "text"}]
                       :isError false
                       :structuredContent {:result 3}}}
             response)))))

(deftest error-handling-test
  (testing "Returns error on invalid method"
    (let [request {:jsonrpc "2.0"
                   :id 999
                   :method "nonexistent/method"
                   :params {}}

          response (stateless/invoke handler request)]

      (is (= {:error {:code -32603
                      :data {:type "io.modelcontextprotocol.spec.McpError"}
                      :message "Missing handler for request type: nonexistent/method"}
              :id 999
              :jsonrpc "2.0"}
             response)))))

(deftest resources-test
  (testing "Can list available resources"
    (let [request {:jsonrpc "2.0"
                   :id 10
                   :method "resources/list"
                   :params {}}

          response (stateless/invoke handler request)]

      (is (= {:id 10
              :jsonrpc "2.0"
              :result {:resources [{:description "The project's README file"
                                    :mimeType "text/markdown"
                                    :name "Project README"
                                    :uri "custom://readme"}]}}

             response)))))

(deftest read-resource-test
  (testing "Can read a resource"
    (let [request {:jsonrpc "2.0"
                   :id 11
                   :method "resources/read"
                   :params {:uri "custom://readme"}}

          response (stateless/invoke handler request)]

      (is (= {:id 11
              :jsonrpc "2.0"
              :result {:contents [{:mimeType "text/markdown"
                                   :text "# Test README\n\nThis is a test."
                                   :uri "custom://readme"}]}}

             response)))))
