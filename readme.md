# MCP 2000XL

<img src="/docs/mcp.jpg" align="right" height="200px" />

A toolkit for building MCP ([Model Context Protocol](https://modelcontextprotocol.io/docs/getting-started/intro)) servers. It uses Anthropic's [Java SDK](https://github.com/modelcontextprotocol/java-sdk) for protocol handling, but stays out of your way when it comes to the transport layer. This means you can implement your MCP servers in more flexible ways:

- You can use the provided `mcp2000xl.server.stdio` namespace to create a compliant server using STDIO transport
- Or integrate your MCP server definition into an existing Ring-based server—it works with any compliant adapter (Jetty, HTTP Kit, and more)


Other features and important notes:

- MCP2000XL relies only on the stateless part of the Java SDK, which means you can create servers that can be distributed as native binaries (something that's not possible out of the box with the Java SDK). No custom Jetty adapters are required.

- Because of the above, the resulting MCP server is **stateless** and doesn't manage or create any sessions. In my experience, this hasn't been a major issue—at least for the MCP servers I've built. If you require sessions, you could technically build them yourself, since at all points you have access to the full request data, including session IDs.

- Tool and resource definitions are supplied as Malli schemas, which can be naturally converted to JSON schemas that are used as part of the MCP protocol. Because of this, we can ensure type safety at the edges of the MCP handler.

## Supported features

- [x] tools
- [x] resources
- [ ] prompts (possible via Java interop)
- [ ] templates (possible via Java interop)


# Installation

Add to `deps.edn`:

```clojure
  mpc2000xl/mcp {:git/url "https://github.com/lukaszkorecki/mcp2000xl"
                 :git/sha "<latest sha>"}
```

Clojars release coming soon.

# Usage


Let's create a handler for our server - it will expose two tools and once resource.

``` clojure
(require '[mcp2000xl.handler :as mcp])

(def handler
  (handler/create {:name "calculator"
                    :version "1.0.0"
                    :tools [{:name "add"
                             :description "Adds two numbers"
                             :input-schema [:map [:a int?] [:b int?]]
                             :output-schema [:map [:result int?]]
                             :handler (fn [{:keys [a b]}] {:result (+ a b)})}

                            {:name "multiply"
                             :description "Multiplies two numbers"
                             :input-schema [:map [:a int?] [:b int?]]
                             :output-schema [:map [:result int?]]
                             :handler (fn [{:keys [a b]}] {:result (* a b)})}]

                    :resources [{:url "custom://greet"
                                 :name "Greet Resource"
                                 :description "A simple greeting resource"
                                 :mime-type "text/plain"
                                 :handler (fn [_request]
                                            ["Hello from the calculator server!"])}]}))

```

From here, you can use the handler in a Ring server. Let's assume we are serving MCP from the `/mcp` endpoint:


```clojure
(defn handle-mcp [{:keys [body] :as _request}]
  ;; body here is expected to be a JSON-RPC request map,
  ;; this assumes your Ring middleware is set up and parsing the request body & serializing responses as JSON
  (let [response (mcp/invoke handler body)]
    {:status 200
     :body response}))


;; then in your router, using Reitit as an example:
(def app
 (ring/ring-handler
    (ring/router [["/mcp" {:post handle-mcp}]])))

```


Creating a STDIO server is simpler:


```clojure

(require '[mcp2000xl.server.stdio :as mcp.stdio])

(defn -main []
  (mcp.stdio/create {:name "calculator"
                     :version "1.0.0"
                     :tools [{:name "add"
                              :description "Adds two numbers"
                              :input-schema [:map [:a int?] [:b int?]]
                              :output-schema [:map [:result int?]]
                              :handler (fn [{:keys [a b]}] {:result (+ a b)})}

                             {:name "multiply"
                              :description "Multiplies two numbers"
                              :input-schema [:map [:a int?] [:b int?]]
                              :output-schema [:map [:result int?]]
                              :handler (fn [{:keys [a b]}] {:result (* a b)})}]

                     :resources [{:url "custom://greet"
                                  :name "Greet Resource"
                                  :description "A simple greeting resource"
                                  :mime-type "text/plain"
                                  :handler (fn [_request]
                                             ["Hello from the calculator server!"])}]}))

;; NOTE: you can also re-use an existing handler:
(mcp.stdio/create {:handler handler})

```

The STDIO server will start up and serve requests. Note that it doesn't perform a clean shutdown yet, but that's coming soon.

Additionally, the `dev-resources` directory contains runnable example servers using both Ring and STDIO.


## Creating tools and resources

All inputs are validated using Malli schemas, see `mcp2000xl.schema` for more details.


# Notes

Huge thanks to the Latacora team for starting https://github.com/latacora/mcp-sdk. This project initially started as a fork in which I wanted to add resource and prompt support, but things quickly evolved beyond that scope.
