VERSION := 0.1.0

R := https://github.com/makeplus/makes
M := .cache/makes
$(shell [ -d '$M' ] || git clone -q $R '$M')

include $M/init.mk
include $M/bun.mk
include $M/graalvm.mk
include $M/clojure.mk
include $M/clean.mk
include $M/shell.mk

UBER-JAR := target/mandy-$(VERSION)-standalone.jar

MAKES-CLEAN := mandy
MAKES-REALCLEAN := dist bin release .cpcache
MAKES-DISTCLEAN := node_modules


build: mandy-cli mandy-ui

jar: $(UBER-JAR)

# 1. Clojure Component (Native Image)
mandy-cli: mandy

# 2. TUI Component (Compiled Bun Binary)
mandy-ui: $(BUN)
	bun install
	bun run build

mandy: $(UBER-JAR) $(GRAALVM)
	native-image \
	  -jar $< \
	  mandy \
	  --no-fallback \
	  --initialize-at-build-time \
	  -H:+UnlockExperimentalVMOptions \
	  -H:IncludeResources=base.ys \
	  -H:+ReportExceptionStackTraces
	@echo "Created mandy native binary"

$(UBER-JAR): $(CLOJURE) src/clj/mandy/main.clj plugins/base.clj plugins/base.ys
	rm -rf target/uber target/classes
	mkdir -p target/uber target/classes
	cp plugins/* target/classes/
	clojure -M -e "(binding [*compile-path* \"target/classes\"] (compile 'mandy.main))"
	for j in $$(clojure -Spath | tr ':' '\n' | grep '\.jar$$'); do \
	  (cd target/uber && jar xf "$$j"); \
	done
	cp -r target/classes/* target/uber/
	jar cfe $@ mandy.main -C target/uber .
	rm -rf target/uber target/classes

zsh-shell: mandy-ui
	ZDOTDIR=$$PWD zsh -is eval "source .mandyrc"

clean::
	rm -rf target

test: test-debug test-sanitized test-tokens test-tui-select test-tui-quit test-tui-search

test-debug: $(CLOJURE)
	clojure -M -m mandy.main pwd --debug

test-sanitized: $(CLOJURE)
	clojure -M -m mandy.main pwd --strip

test-tokens: $(CLOJURE)
	clojure -M -m mandy.main ls --debug | ys -J -

test-tui-select: mandy-ui $(BUN)
	@util/make test-tui-select

test-tui-quit: mandy-ui $(BUN)
	@util/make test-tui-quit

test-tui-search: mandy-ui $(BUN)
	@util/make test-tui-search

debug-tui: mandy-ui $(BUN)
	@CMD=$(or $(CMD),ls) util/make debug-tui

debug-payload:
	@CMD=$(or $(CMD),ls) util/make debug-payload

run-tui: mandy-ui $(BUN)
	@util/make run-tui

# 3. Release Orchestration
release-all: clean $(BUN)
	@VERSION=$(VERSION) util/make release-all
