(ns mcp2000xl.impl.json
  (:require
   [jsonista.core :as jsonista])
  (:import
   (io.modelcontextprotocol.json.jackson JacksonMcpJsonMapper)))

(def ^JacksonMcpJsonMapper mapper
  (JacksonMcpJsonMapper. jsonista/default-object-mapper))
