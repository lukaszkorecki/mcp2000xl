fmt:
	clojure-lsp format

lint:
	clojure-lsp diagnostics


check:
	clojure -M:check

test:
	clojure -M:dev:test


.PHONY: fmt lint check test
