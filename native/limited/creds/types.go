package creds

// This file is the seam between the transport and the credential source (`creds`): it declares only the boundary
// types the transport consumes — the error-kind enum, the error type and the result struct. It holds no
// service-interaction logic, no requests, no secrets. The transport mirrors these kinds in limited.CredErrorKind
// (see provider.go).

// ErrorKind tells the transport how to react to a Fetch failure. Mirrored by limited.CredErrorKind; keep the order.
type ErrorKind int

const (
	// Transient: a temporary failure; the transport retries this callID with backoff.
	Transient ErrorKind = iota
	// RateLimited: the service is throttling; the transport waits longer and may try another callID.
	RateLimited
	// IdentifierDead: this callID is unusable; the transport stops trying it for this run.
	IdentifierDead
	// Unavailable: nothing can be served right now; the transport abandons the server and lets the engine fall back.
	Unavailable
	// Fatal: unrecoverable; the transport stops this server and emits a fatal error event.
	Fatal
)

// FetchError lets Fetch tell the transport what went wrong. Code is a short neutral token for the detailed log;
// Message is shown to the user through the app's log. Keep both neutral (no service names).
type FetchError struct {
	Kind    ErrorKind
	Code    string
	Message string
}

func (e *FetchError) Error() string { return e.Code + ": " + e.Message }

// Credentials is what Fetch returns: the TURN relay endpoints and how to authenticate to them. TurnURLs may be
// "turn:"/"turns:" URIs or plain host:port — the transport reduces them. TTL is seconds, 0 = unknown.
type Credentials struct {
	TurnURLs []string
	Username string
	Password string
	TTL      int
}
