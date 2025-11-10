A Clojure library for easily building compliant Model Context Protocol (MCP) servers. It achieves
this by wrapping [the official Java SDK](https://github.com/modelcontextprotocol/java-sdk) as a ring
handler that can be embedded into Clojure applications while still leveraging Clojure middleware and
routing. This makes it easy to use the most recommended transport (Streamable Http), while not
complicating your Clojure apps by needing to mount multiple servlets.

---

## Installation

```clojure
com.latacora/mcp-sdk {:git/url "https://github.com/latacora/mcp-sdk"
                      :git/sha "<latest-commit-sha>"}
```

---

## Usage

```clojure 
(require '[com.latacora.mcp.core :as mcp])

; for tools, this library provides a convenient way to construct 
; tool specifications using malli schemas for input and output 
; validation (as well as json-schema generation)
(def tool
  (mcp/create-tool-specification
    {:name          "add"
     :title         "Add two numbers"
     :description   "Adds two numbers together"
     :input-schema  [:map [:a int?] [:b int?]]
     :output-schema [:map [:result int?]]
     :handler       (fn [_exchange {:keys [a b]}]
                      {:result (+ a b)})}))

; when you construct the mcp handler you provide java objects
; like the underlying modelcontextprotocol requests. this means
; you're free to build any of the other mcp server features using
; interop. at a future date we might provide clojure wrappers to
; make constructing them easier, but for now we only provide one for
; tools since they're the most common and tedious to construct with interop.
(def handler (mcp/create-ring-handler {:name "hello-world" :version "1.0.0" :tools [tool]}))

; note that this is a custom run-jetty function that preserves
; the original servlet request/response objects in metadata so
; they can be passed to the embedded mcp servlet. it is a drop-in
; replacement for ring.adapter.jetty/run-jetty. here we perform no
; routing and so the mcp server is mounted at the root path, but you
; can embed the handler anywhere in your routing tree.
(def server (mcp/run-jetty handler {:port 3000 :join? false}))

```

---

## Testing

You can use the [mcp inspector](https://modelcontextprotocol.io/docs/tools/inspector)
to test your MCP server.

```bash 
npx @modelcontextprotocol/inspector --server-url http://localhost:3000
```

---

## License

Copyright © Latacora

Distributed under the Eclipse Public License version 2.0.