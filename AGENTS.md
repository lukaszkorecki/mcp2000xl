# MCP2000XL - Agent Development Guide

## Project Overview

**MCP2000XL** is a Clojure wrapper around Anthropic's Java SDK for the Model Context Protocol (MCP). The name is a fun homage to Akai's MPC2000XL sampler.

### Goals

1. Provide a clean, idiomatic Clojure API for building MCP servers
2. Support both STDIO and HTTP transport protocols
3. Enable GraalVM native-image compilation (future goal)
4. Keep the `(create-server {...})` user-facing API pattern

### Non-Goals

- Embedding into Jetty or any specific web server
- Providing custom HTTP server implementations (users bring their own)
- Supporting Ring middleware patterns (removed in hard fork)

## Project Structure

```
src/mcp2000xl/
├── tool.clj      - Tool specification creation with Malli schema validation
├── resource.clj  - Resource specification creation
└── server.clj    - MCP server building and configuration

test/mcp2000xl/
└── tool_test.clj - Tool specification tests
```

## Architecture

### Namespace Organization

- **`mcp2000xl.tool`**: Creates tool specifications using Malli schemas for input/output validation
  - Exports: `create-tool-specification`
  - Uses Malli for schema validation and JSON Schema generation
  - Handles validation errors gracefully with logging

- **`mcp2000xl.resource`**: Creates resource specifications
  - Exports: `create-resource-specification`
  - Resources are URI-based with MIME type support
  - Error handling returns error content to client

- **`mcp2000xl.server`**: Builds MCP server instances
  - Exports: `build-mcp-server`
  - Currently only supports HTTP transport via `HttpServletStreamableServerTransportProvider`
  - Returns map with `:transport-provider` and `:mcp-server` keys

### Key Design Decisions

1. **Hard Fork from Latacora**: This is a hard fork that removed Jetty-specific code to focus on wrapping the MCP SDK directly

2. **Transport Support**: The MCP Java SDK provides:
   - `StdioServerTransportProvider` - for STDIO transport
   - `HttpServletStreamableServerTransportProvider` - for HTTP servlet-based transport
   - `HttpServletSseServerTransportProvider` - for Server-Sent Events

3. **No Built-in Web Server**: Users should bring their own HTTP server (Jetty, Undertow, http-kit, etc.) and mount the servlet as needed

4. **Malli Integration**: Tools use Malli schemas for validation and automatic JSON Schema generation

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
     (.completions ^List completions)  ;; This is correct
     (.tools ^List tools))
   ```

4. **Formatting**: Use `clojure-lsp format` to maintain consistent style

5. **Linting**: Run `clojure-lsp diagnostics` before committing
   - Fix all warnings except `unused-public-var` for exported API functions

### Testing

- Run tests with: `clj -M:dev -e "(require 'mcp2000xl.tool-test) (clojure.test/run-tests 'mcp2000xl.tool-test)"`
- Check for reflection warnings during test runs
- All tests should pass before committing

### Dependencies

- **Core**: Clojure, MCP SDK (Java), Jackson for JSON
- **Validation**: Malli for schemas
- **Logging**: tools.logging
- **Servlet API**: Jakarta Servlet (required by MCP SDK, but no server implementation)

### Common Tasks

```bash
# Format code
clojure-lsp format

# Check for issues
clojure-lsp diagnostics

# Run tests
clj -M:dev -e "(require 'mcp2000xl.tool-test) (clojure.test/run-tests 'mcp2000xl.tool-test)"

# Check for reflection warnings
clj -M:dev -e "(require 'mcp2000xl.tool 'mcp2000xl.resource 'mcp2000xl.server)" 2>&1 | grep -i reflect
```

## TODO / Future Work

### High Priority

1. **Add STDIO Transport Support**
   - Create factory function for STDIO servers
   - Decide on API: separate `create-stdio-server` vs unified `create-server` with `:transport` option

2. **HTTP Transport Refinement**
   - Document how users should mount the servlet in their web servers
   - Provide examples for common servers (Jetty standalone, http-kit, Undertow)
   - Consider if we need helpers for servlet mounting

3. **API Design Questions** (need user input)
   - How should users choose between STDIO and HTTP?
   - Option A: `(create-stdio-server {...})` and `(create-http-server {...})`
   - Option B: `(create-server {:transport :stdio ...})`
   - Option C: Different setup functions for each transport

### Medium Priority

4. **GraalVM Native Image Support**
   - Test compilation with GraalVM
   - Add reflection configuration if needed
   - Document native image build process

5. **Documentation**
   - Add comprehensive README with examples
   - Document the user-facing API
   - Show integration with popular web servers
   - Migration guide from Latacora's version

6. **Testing**
   - Add tests for resource specifications
   - Add integration tests for server building
   - Test STDIO transport when implemented

### Low Priority

7. **Additional Features**
   - Prompts support (currently just passed through)
   - Completions support
   - Resource templates

## MCP SDK Integration Notes

The Java SDK classes we interact with:

- **Server Building**: `McpServer`, `McpServer.sync()`
- **Transport Providers**: 
  - `HttpServletStreamableServerTransportProvider`
  - `StdioServerTransportProvider`
- **Specifications**:
  - `McpServerFeatures$SyncToolSpecification`
  - `McpServerFeatures$SyncResourceSpecification`
- **Schema Objects**: `McpSchema$*` classes for all protocol types
- **JSON Mapping**: `JacksonMcpJsonMapper`

## Git Workflow

- Work on `hard-fork` branch (or feature branches)
- Commit messages should be descriptive
- Run tests and linting before committing
- Use conventional commits format when possible

## Contact & Contribution

This is a hard fork with different goals than the original Latacora project. When contributing:

1. Follow the code style guidelines above
2. Run linting and tests
3. Focus on the project goals (clean Clojure wrapper, GraalVM support)
4. Ask questions before major architectural changes
