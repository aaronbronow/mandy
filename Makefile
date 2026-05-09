.PHONY: clean uber native test test-debug test-sanitized test-tokens build-ts

mandy: native

target/mandy-0.1.0-standalone.jar: src/clj/mandy/main.clj deps.edn build.clj
	clj -T:build uber

uber: target/mandy-0.1.0-standalone.jar

native: target/mandy-0.1.0-standalone.jar
	native-image -H:Name=mandy \
	             -H:+ReportExceptionStackTraces \
	             --no-fallback \
	             --initialize-at-build-time \
	             --report-unsupported-elements-at-runtime \
	             -jar target/mandy-0.1.0-standalone.jar

build-ts:
	npm run build

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
