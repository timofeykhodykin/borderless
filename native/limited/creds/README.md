# `creds` — the service-facing component

This package is the only part of the native limited-network core that talks to an external service. The transport in
the parent package uses it through `CredentialProvider` (`../provider.go`): given a call identifier, `Fetch` returns
TURN endpoints plus the credentials for them, or a `FetchError` that says how the core should react.

**Read this package only when a task concerns it.** The transport, the app and the other documents do not depend on
its internals; everything else about the core is in `..` and `local/native-core/DESIGN.md`.

## Files

| File | What |
|---|---|
| `types.go` | boundary types: `ErrorKind`, `FetchError`, `Credentials` |
| `endpoints.go` | endpoints and client credentials, stored encoded |
| `creds.go` | `Fetch`, the two request paths, request builders, error classification |
| `http.go` | the HTTP client used for the requests |
| `names.go` | name data for join requests, stored encoded |
| `obf.go` | decodes the stored values at start-up |
| `creds_test.go`, `fetch_test.go` | unit tests (no network) |

## Contract the core relies on

- No caching here: the core caches per call identifier and asks again near the TTL and after a TURN authentication
  failure.
- Failures come back as `FetchError` with an `ErrorKind`; when both request paths fail, the more informative error is
  returned.
- TURN endpoints are normalised and deduplicated here; the transport keeps their transport (`turn:`/`turns:`,
  `transport=tcp`) and tries UDP first, then TCP/TLS.
- Answers are never logged; a decode error carries no body. The transport's log redacts key material.
- Wire values (endpoints, client credentials, name data) are stored encoded and decoded once at start-up. Keep them
  encoded when editing — `TestEncodedDataDecodes` fails if they stop decoding.
- Request pacing (the service rate-limits requests) is enforced inside `Fetch`.

## Tests

```
./gradlew :app:testLimited
```

(`go vet` + `go test ./...` with the pinned Go; all of Go's caches stay in `.gradle/go` — never run a system `go`
here, it fills `~/go` and `~/Library/Caches/go-build`.)
