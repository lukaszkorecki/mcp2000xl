(ns mcp2000xl.examples.http-server
  (:require
   [clojure.tools.logging :as log]
   [com.stuartsierra.component :as component]
   [mcp2000xl.stateless :as mcp]
   [utility-belt.component.jetty :as jetty]
   [utility-belt.component.system :as sys]
   [ring.middleware.defaults :as ring-defaults]
   [ring.middleware.json :as ring-json]))

(defn router [{:keys [uri request-method component body] :as _req}]
  (case
   [request-method uri]
    ([:post "/mcp"] [:post "mcp/"]) (let [{:keys [mcp]} component]
                                      (log/infof "Received MCP request: %s" body)
                                      {:status 200
                                       :body (mcp/invoke mcp body)})

    #_else {:status 404
            :body "Not Found"}))

(def handler
  (-> router
      (ring-json/wrap-json-response)
      (ring-defaults/wrap-defaults ring-defaults/api-defaults)
      (ring-json/wrap-json-body {:keywords? true})))

(defn system []
  {:mcp (mcp/create-handler {:name "ayo" :version "0.1.0"
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
                                                     ["Hello, World!"])}]})

   :http-server (component/using
                 (jetty/create
                  {:config {:port 8083}
                   :handler handler})
                 [:mcp])})

(def !sys (atom nil))

(defn -main []

  (sys/setup-for-production {:store !sys
                             :service "http-mcp-server"
                             :component-map-fn mcp2000xl.examples.http-server/system}))
