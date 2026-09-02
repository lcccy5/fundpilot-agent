.PHONY: run test verify race build

run:
	go run ./cmd/server

test:
	go test ./...

verify:
	go fmt ./...
	go vet ./...
	go test ./...

race:
	CGO_ENABLED=1 go test -race ./...

build:
	go build -o bin/fund-agent ./cmd/server
