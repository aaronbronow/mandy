.PHONY: clean uber mandy-cli mandy-ui test test-debug test-sanitized test-tokens test-tui-select test-tui-quit test-tui-search build

all: mandy-cli mandy-ui

mandy: mandy-cli

build: mandy-cli mandy-ui

mandy-cli:
	@echo '#!/usr/bin/env bash' > mandy
	@echo 'clj -M -m mandy.main "$$@"' >> mandy
	@chmod +x mandy
	@echo "Created mandy wrapper"

mandy-ui:
	npm run build

shell: mandy-ui
	ZDOTDIR=$$PWD zsh -is eval "source .mandyrc"

clean:
	clj -T:build clean
	rm -f mandy
	rm -rf dist bin

test: test-debug test-sanitized test-tokens test-tui-select test-tui-quit test-tui-search

test-debug:
	clj -M -m mandy.main pwd --debug

test-sanitized:
	clj -M -m mandy.main pwd --strip

test-tokens:
	clj -M -m mandy.main ls --debug | ys -J -

test-tui-select: mandy-ui
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main ls) ; \
	rm -f /tmp/mandy_buffer ; \
	MANDY_TEST_KEYS="ENTER,ENTER" MANDY_PAYLOAD_PATH=$$PAYLOAD_PATH bun run src/index.ts ; \
	if [ -f /tmp/mandy_buffer ]; then \
		echo "TUI Select Test Passed: $$(cat /tmp/mandy_buffer)" ; \
		rm /tmp/mandy_buffer ; \
	else \
		echo "TUI Select Test Failed: No buffer created" ; exit 1 ; \
	fi

test-tui-quit: mandy-ui
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main ls) ; \
	rm -f /tmp/mandy_buffer ; \
	MANDY_TEST_KEYS="q" MANDY_PAYLOAD_PATH=$$PAYLOAD_PATH bun run src/index.ts ; \
	if [ -f /tmp/mandy_buffer ]; then \
		echo "TUI Quit Test Failed: Buffer was created" ; rm /tmp/mandy_buffer ; exit 1 ; \
	else \
		echo "TUI Quit Test Passed" ; \
	fi

test-tui-search: mandy-ui
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main ls) ; \
	rm -f /tmp/mandy_buffer ; \
	MANDY_TEST_KEYS="/,-,-,a,l,l,ENTER,ENTER,ENTER,ENTER" MANDY_PAYLOAD_PATH=$$PAYLOAD_PATH bun run src/index.ts ; \
	if [ -f /tmp/mandy_buffer ] && grep -q "\--all" /tmp/mandy_buffer; then \
		echo "TUI Search Test Passed: $$(cat /tmp/mandy_buffer)" ; \
		rm /tmp/mandy_buffer ; \
	else \
		echo "TUI Search Test Failed: Expected --all in buffer" ; \
		[ -f /tmp/mandy_buffer ] && echo "Found: $$(cat /tmp/mandy_buffer)" ; \
		exit 1 ; \
	fi

debug-tui: mandy-ui
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main $(or $(CMD),ls)) ; \
	MANDY_PAYLOAD_PATH=$$PAYLOAD_PATH bun run src/index.ts $(or $(CMD),ls)

debug-payload:
	@PAYLOAD_PATH=$$(MANDY_DRY_RUN=1 clj -M -m mandy.main $(or $(CMD),ls)) || { echo "" > .mandy_payload_path ; exit 1 ; } ; \
	echo $$PAYLOAD_PATH > .mandy_payload_path ; \
	cat $$PAYLOAD_PATH ; \
	echo "\nPayload saved to: $$PAYLOAD_PATH"

run-tui: mandy-ui
	@P_PATH=$$( [ -f .mandy_payload_path ] && cat .mandy_payload_path ) ; \
	FINAL_PATH=$${FILE:-$${MANDY_PAYLOAD_PATH:-$$P_PATH}} ; \
	if [ -z "$$FINAL_PATH" ] || [ ! -f "$$FINAL_PATH" ]; then \
		echo "Error: No valid payload found. Run 'make debug-payload' first or provide FILE="; \
		exit 1; \
	fi ; \
	MANDY_PAYLOAD_PATH=$$FINAL_PATH bun run src/index.ts

# Release Packaging
OS := $(shell uname -s | tr '[:upper:]' '[:lower:]')
ARCH := $(shell uname -m)
VERSION := 0.1.0

dist: mandy-cli mandy-ui
	mkdir -p dist-pkg/bin
	cp mandy dist-pkg/bin/
	cp bin/mandy-ui dist-pkg/bin/
	tar -czf mandy-v$(VERSION)-$(OS)-$(ARCH).tar.gz -C dist-pkg .
	rm -rf dist-pkg
	@echo "Created release: mandy-v$(VERSION)-$(OS)-$(ARCH).tar.gz"
