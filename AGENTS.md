# MCP2000XL - Agent Development Guide

## Project Overview

**MCP2000XL** is a Clojure wrapper around Anthropic's Java SDK for the Model Context Protocol (MCP). The name is a fun homage to Akai's MPC2000XL sampler.

### Goals

1. Provide a clean, idiomatic Clojure API for building MCP servers
2. Support both STDIO and HTTP protocols
3. Enable GraalVM native-image compilation (future goal)
4. Data-driven: tools and resources defined as plain Clojure maps

### Non-Goals

- Embedding into Jetty or any specific web server
- Assuming any particular HTTP server implementation
- Complex middleware chains (keep it simple)

## Project Structure

Run
```
git ls-files
```

## Architecture

### Public API Namespaces

**`mcp2000xl.schema`** - Validates tool and resource definitions
- `validate-tool` - Validates a tool definition map
- `validate-resource` - Validates a resource definition map
- `validate-tools` - Validates collection of tools
- `validate-resources` - Validates collection of resources

**`mcp2000xl.server.stdio`** - STDIO server
- `create` - Creates and starts STDIO server (blocks forever)
- `process-line!` - Process a single JSON-RPC request line
- Takes plain maps for tools/resources
- Perfect for Claude Desktop integration

**`mcp2000xl.handler`** - MCP request handler
- `create` - Creates handler from tool/resource definitions
- `invoke` - Invokes handler with Clojure data (not JSON!)
- For Ring integration, web frameworks, and STDIO server

### Internal Implementation

**`mcp2000xl.impl.tool`** - Builds tool specifications
- Extracts common handler logic
- `build-tool` creates Java SDK specs
- `build-tools` builds collection

**`mcp2000xl.impl.resource`** - Builds resource specifications
- Extracts common BiFunction handler
- `build-resource` creates Java SDK specs
- `build-resources` builds collection

### Key Design Decisions

1. **Data-Driven API**: Tools and resources are plain Clojure maps
   ```clojure
   {:name "add"
    :input-schema [:map [:a int?] [:b int?]]
    :output-schema [:map [:result int?]]
    :handler (fn [{:keys [a b]}] {:result (+ a b)})}
   ```

2. **Simple Architecture**: One way to build MCP servers
   - Create a handler with `mcp2000xl.handler/create`
   - Use it with STDIO transport or invoke directly for HTTP

3. **No Duplication**: Common logic extracted
   - Resource: BiFunction handler for operations
   - Tool: Result building and error handling

4. **Transport Support**:
   - STDIO: Line-based JSON-RPC protocol over stdin/stdout
   - HTTP: Direct handler invocation for Ring/web frameworks

5. **Malli Integration**: Schemas validated at creation time

## Development Guidelines

### Code Style

1. **Reflection Warnings**: All namespaces must have `(set! *warn-on-reflection* true)`

2. **Clojure 1.12 Java Interop**: Use the new syntax for standalone method calls:
   ```clojure
   ;; Good (standalone calls)
   (Thread/.stop thread)
   (StringWriter/.toString sw)

   ;; Old style (only use inside doto/-> threading)
   (.stop ^Thread thread)
   ```

3. **Type Hints in doto**: Keep traditional type hints inside `doto` forms:
   ```clojure
   (doto builder
     (.completions ^List completions)
     (.tools ^List tools))
   ```

4. **Formatting**: Use `clojure-lsp format` to maintain consistent style

5. **Linting**: Run `clojure-lsp diagnostics` before committing
   - Fix all errors
   - Review warnings and info messages
   - Pay attention to unused code warnings (likely indicates over-engineering)
   - Public API functions may show as "unused" - that's OK

### Testing

- Run all tests:  `make test`
- Run linter: `make lint`
- Run reflection checks: `make check`
- Format code: `make fmt`
- Trim whitespace: `make trim`
- Show all commands: `make help`

### Common Tasks

#### Development Workflow
```bash
# 1. Make changes
vim src/mcp2000xl/...

# 2. Format and trim whitespace
make fmt
make trim

# 3. Check for issues
make lint

# 4. Verify no reflection
make check

# 5. Run tests
make test
```

### Linting Notes

The linter will catch:
- **Errors**: Must fix (syntax errors, missing vars, etc.)
- **Warnings**: Should fix (suspicious code, potential bugs)
- **Info**: Review carefully (often style issues)
- **Unused code**: Indicates over-engineering - remove it!

Public API functions in `mcp2000xl.server.stdio` and `mcp2000xl.handler` may show as "unused" because tests use internal impl namespaces directly. This is OK.

## Usage Examples

### STDIO Server (Claude Desktop)

```clojure
(require '[mcp2000xl.server.stdio :as stdio])

(def my-tool
  {:name "weather"
   :description "Get weather for a city"
   :input-schema [:map [:city string?]]
   :output-schema [:map [:temp int?] [:condition string?]]
   :handler (fn [{:keys [city]}]
              {:temp 72 :condition "sunny"})})

(stdio/create {:name "weather-server"
               :version "1.0.0"
               :tools [my-tool]})
;; Blocks forever, reads JSON-RPC from stdin/writes to stdout
```

### HTTP Handler (Ring)

```clojure
(require '[mcp2000xl.handler :as handler])

(def mcp-handler (handler/create
                   {:name "api-server"
                    :version "1.0.0"
                    :tools [my-tool]}))

(defn ring-handler [request]
  (if (and (= "/mcp" (:uri request))
           (= :post (:request-method request)))
    (let [response (handler/invoke mcp-handler (:body request))]
      {:status 200
       :headers {"Content-Type" "application/json"}
       :body response})
    {:status 404 :body "Not found"}))
```

## MCP SDK Integration

Key Java classes:
- **Server**: `McpServer`, `McpStatelessServerHandler`
- **Transport**: Custom transport via `McpStatelessServerTransport`
- **Specs**: `McpStatelessServerFeatures$Sync*Specification`
- **Schema**: `McpSchema$Tool`, `McpSchema$Resource`, `McpSchema$CallToolResult`, `McpSchema$JSONRPCRequest`, `McpSchema$JSONRPCResponse`
- **JSON**: `JacksonMcpJsonMapper`, `jsonista.core` for efficient JSON handling

## TODO

### High Priority
1. Add examples to README
2. Test GraalVM native-image compilation
3. Add prompt support (currently not exposed)

### Medium Priority
4. Add resource templates support
5. More comprehensive integration tests
6. Performance benchmarks

### Low Priority
7. Completions support
8. More advanced schema validation features

## Git Workflow

- Work on `hard-fork` branch
- Descriptive commit messages
- Run all checks before committing
- Use conventional commits when possible
