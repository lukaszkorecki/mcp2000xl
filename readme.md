# MCP 2000XL

<img src="/docs/mcp.jpg" align="right" height="200px" />

A toolkit for building MCP ([Model Context Protocol](https://modelcontextprotocol.io/docs/getting-started/intro)) servers.
It uses Anthropic's [Java SDK](https://github.com/modelcontextprotocol/java-sdk) for the protocol handling, but stays out of your way
when it comes to the transport itself. This means that you can implement your MCP servers in more flexible ways:

- you can use provided `mcp2000xl.server.stdio` namespace to create a compliant server using STDIO transport
- or integrate your MCP server definition into existing Ring-based server, it works with any compliant adapter (Jetty, HTTP Kit and more)


Other features and important notes:

- because MCP2000XL relies only on stateless part of the Java SDK - you can create servers that can be distributed as
  native binaries (something that's not quite possible out of the box with Java SDK) no custom Jetty adapters required

- because of the above, resulting MCP server is **stateless** and doesn't manage or create any sessions, in my
  experience that's not a huge issue - at least in case of MCP servers that I've built if you require sessions, you

- could technically build them yourself, since at all points you have access to full request data, including session IDs
  etc tool, resource etc definitions are supplied as Malli schemas, which can be naturally converted to JSON Schemas

- that are used as part of the MCP protocol, because of that we can ensure type safety at the edges of the MCP handler

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

From here, you can use handler in a Ring server. Let's assume we are serving MCP from `/mcp` endpoint:


```clojure
(defn handle-mcp [{:keys [body] :as _request}]
  ;; body here is expected to be a JSON RPC request map,
  ;; this assumes your Ring middleware is setup, and parsing request body & serializing responses as JSON
  (let [response (mcp/invoke handler body)]
    {:status 200
     :body response}))


;; then in your router, using Reitit as example:
(def app
 (ring/ring-handler
    (ring/router [["/mcp" {:post handle-mcp}]])))

```


Creating a STDIO servers is simpler:


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

;; NOTE:  you can also re-use existing handler:
(mcp.stdio/create {:handler handler})

```

STDIO server will start up and serve requests. Note that it doesn't perform a clean shutdown just yet, but that's
coming.
Additionally, `dev-resources` directory contains runnbale example servers using Ring and STDIO.


## Creating tools, resources etc

All inputs are validated using Malli schemas, see `mcp2000xl.schema` for more details.


# Notes

Huge thanks to Latacora team for starting https://github.com/latacora/mcp-sdk - this project initially started as a fork
in which I wanted to add resource and prompt support, but things quickly got out of hand.
