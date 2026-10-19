# `native/limited` — the limited-network core

Go module `app.borderless/limited`: the transport that carries the tunnel to a server through a calls service, the
app-facing API (`Config`, `Events`, `Session`, `Start`), and the program the app runs (`cmd/limitedd`, packaged as
`lib/<abi>/liblimited.so`).

- **Transport:** `core.go`, `session.go`, `turn.go`, `media.go`, `wrap.go`, `dispatcher.go`, `control.go`.
  `turn.go` parses the service's TURN URLs and tries UDP first, then TCP/TLS; `POLICY:max_workers=N` lowers the
  stream ceiling and is reported to the app (`OnPolicy`).
- **Seam:** `provider.go` (`CredentialProvider`, `TurnCreds`, `CredError`) and `provider_impl.go` (the adapter).
- **Service-facing package:** `creds/` — it has its own README; read it only when the task concerns it.

Build: the app's Gradle task `buildLimited` (pinned Go, modules verified against `go.sum`). Tests:

```
./gradlew :app:testLimited
```

(`go vet` + `go test ./...` with the pinned Go; all of Go's caches stay in `.gradle/go` — never run a system `go`
here, it fills `~/go` and `~/Library/Caches/go-build`.)
