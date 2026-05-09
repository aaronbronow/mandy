.PHONY: clean uber native test test-debug test-sanitized test-tokens build-ts

mandy: native

build: build-ts

build-ts:
	npm run build

shell: build-ts
	ZDOTDIR=$$PWD zsh -is eval "source .mandyrc"

clean:
	clj -T:build clean
	rm -f mandy
	rm -rf dist

test: test-debug test-sanitized

test-debug:
	clj -M -m mandy.main pwd --debug

test-sanitized:
	clj -M -m mandy.main pwd --strip

test-tokens:
	clj -M -m mandy.main ls --debug | ys -J -

debug-tui: build-ts
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main $(or $(CMD),ls)) ; \
	MANDY_PAYLOAD_PATH=$$PAYLOAD_PATH node dist/index.js $(or $(CMD),ls)

debug-payload:
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main $(or $(CMD),ls)) || { echo "" > .mandy_payload_path ; exit 1 ; } ; \
	echo $$PAYLOAD_PATH > .mandy_payload_path ; \
	cat $$PAYLOAD_PATH ; \
	echo "\nPayload saved to: $$PAYLOAD_PATH"

run-tui: build-ts
	@P_PATH=$$( [ -f .mandy_payload_path ] && cat .mandy_payload_path ) ; \
	FINAL_PATH=$${FILE:-$${MANDY_PAYLOAD_PATH:-$$P_PATH}} ; \
	if [ -z "$$FINAL_PATH" ] || [ ! -f "$$FINAL_PATH" ]; then \
		echo "Error: No valid payload found. Run 'make debug-payload' first or provide FILE="; \
		exit 1; \
	fi ; \
	MANDY_PAYLOAD_PATH=$$FINAL_PATH node dist/index.js

# Release Packaging
OS := $(shell uname -s | tr '[:upper:]' '[:lower:]')
ARCH := $(shell uname -m)
VERSION := 0.1.0

dist: native build-ts
	mkdir -p dist-pkg/bin dist-pkg/lib
	cp mandy dist-pkg/bin/
	cp -r dist/* dist-pkg/lib/
	tar -czf mandy-v$(VERSION)-$(OS)-$(ARCH).tar.gz -C dist-pkg .
	rm -rf dist-pkg
	@echo "Created release: mandy-v$(VERSION)-$(OS)-$(ARCH).tar.gz"
