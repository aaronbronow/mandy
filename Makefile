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
	  -H:IncludeResources="(base|ubuntu_discovery)\\.ys" \
	  -H:+ReportExceptionStackTraces
	@echo "Created mandy native binary"

$(UBER-JAR): $(CLOJURE) src/clj/mandy/strategy.clj src/clj/mandy/ubuntu.clj src/clj/mandy/check.clj src/clj/mandy/mcp.clj src/clj/mandy/main.clj plugins/base.clj plugins/base.ys plugins/ubuntu_discovery.ys
	rm -rf target/uber target/classes
	mkdir -p target/uber target/classes
	cp plugins/* target/classes/
	clojure -M -e "(binding [*compile-path* \"target/classes\"] (compile 'mandy.strategy) (compile 'mandy.ubuntu) (compile 'mandy.check) (compile 'mandy.mcp) (compile 'mandy.main))"
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
	rm -f mandy

test: test-debug test-sanitized test-tokens test-tui-select test-tui-quit test-tui-search test-ubuntu

test-ubuntu: mandy
	./mandy discover --intent "case insensitive regex search by file type" --keyword rg
	./mandy discover --intent "compute 256-bit blake2 checksum" --keyword b2sum --verbose
	./mandy discover --intent "find json files" --keyword fd
	./mandy preflight "Evaluate and convert config.yaml using the locally installed ys CLI tool."
	./mandy check --no-strict-preview "rg -i -n -t py retry_limit src/"
	./mandy check --no-strict-preview "if command -v yq >/dev/null 2>&1; then yq '.port' app.yaml; fi"
	./mandy check --no-strict-preview "command -v exiftool >/dev/null 2>&1 || echo UNAVAILABLE"
	./mandy check --no-strict-preview 'printf "abc" | { read -r x; echo "$$x"; }'
	./mandy check --no-strict-preview 'history | tail -n 5'
	@./mandy check "tar -czf bundle.tar.gz project/ --exclude='*.log'" >/dev/null 2>&1 && exit 1 || test $$? -eq 2
	@./mandy check "rsync -av --delete src_dir/ dst_dir/" >/dev/null 2>&1 && exit 1 || test $$? -eq 2
	@./mandy check "rsync -av src/ dst/ && find dst/ -type f -print0 | xargs -0 rm" >/dev/null 2>&1 && exit 1 || test $$? -eq 2
	@./mandy check --enrich "b2sum --length-bits 256 artifact.bin" >/dev/null 2>&1 && exit 1 || test $$? -eq 2
	@./mandy check --enrich "fd -e json project/" >/dev/null 2>&1 && exit 1 || test $$? -eq 2

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
