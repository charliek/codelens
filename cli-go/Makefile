.PHONY: generate build install test lint clean

generate:
	go generate ./...

build: generate
	go build -o ./bin/codelens ./cmd/codelens

install: build
	mkdir -p $(HOME)/.local/bin
	cp ./bin/codelens $(HOME)/.local/bin/codelens
	@if [ "$$(uname)" = "Darwin" ]; then codesign --force --sign - $(HOME)/.local/bin/codelens; fi

test: generate
	go test ./...

lint:
	golangci-lint run

clean:
	rm -rf ./bin
	rm -f ./internal/version/version.txt
