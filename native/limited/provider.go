package limited

import "context"

// CredentialProvider is how the core gets TURN credentials for one call identifier (see local/native-core/DESIGN.md).
//
// It is the service-facing part of the limited-network core: given a call identifier, it returns the calls service's
// TURN relay endpoints and the username/password for them. How those are obtained is entirely inside Fetch and
// unknown to the transport. The transport (this package) consumes the returned credentials to allocate a relay, open
// a DTLS session to the server through it, and carry traffic.
//
// This type never crosses the gomobile boundary; it is pure Go, so an implementation may use any Go types and
// dependencies it likes.
type CredentialProvider interface {
	// Fetch returns TURN credentials for one call identifier.
	//
	//   ctx      the transport cancels it when the credentials are no longer needed (stream closed, server left,
	//            core stopped) and sets its own deadline on it; return ctx.Err() promptly on cancellation.
	//   callID   one identifier from Config.CallIDs.
	//   instance a stable per-server id the transport passes through (not the hardware id); use it to diversify
	//            requests deterministically, or ignore.
	//
	// Must be safe to call concurrently for different callIDs. It is never called again for the same callID while
	// a previous call for it is still running, but it is called again later to refresh (see TurnCreds.TTL).
	// Return a *CredError to tell the transport how to react; a plain error is treated as Transient.
	Fetch(ctx context.Context, callID, instance string) (*TurnCreds, error)
}

// TurnCreds is what Fetch returns: where the TURN relays are and how to authenticate to them.
type TurnCreds struct {
	// URLs are the TURN servers, most-preferred first; at least one. Either "turn:host:port?transport=udp|tcp" URIs
	// (as the service returns them; "turns:" too) or plain "host:port". The transport keeps their scheme and query,
	// drops duplicates, and tries UDP first, then TCP/TLS (a network may drop UDP but allow the stream transports).
	URLs []string
	// Username and Password authenticate to those TURN servers.
	Username string
	Password string
	// TTL is how long (seconds) the credentials stay valid. 0 = unknown: the transport then refreshes only after
	// a TURN authentication failure rather than pre-emptively.
	TTL int64
}

// CredErrorKind tells the transport how to react to a Fetch failure.
type CredErrorKind int

const (
	// Transient: a temporary failure; the transport retries this callID with backoff.
	Transient CredErrorKind = iota
	// RateLimited: the service is throttling; the transport waits longer and may try another callID.
	RateLimited
	// IdentifierDead: this callID is unusable; the transport stops trying it for this run.
	IdentifierDead
	// Unavailable: the provider cannot serve anything right now; the transport abandons the limited-network
	// server and lets the engine fall back as if nothing answered.
	Unavailable
	// Fatal: unrecoverable (e.g. the server rejected the connection password, surfaced here); the transport
	// stops this server and emits a fatal error event.
	Fatal
)

// CredError lets Fetch say what went wrong so the transport reacts correctly. Code is a short, neutral token for
// the detailed log; Message is shown to the user through the app's log. Keep both neutral (no service names).
type CredError struct {
	Kind    CredErrorKind
	Code    string
	Message string
}

func (e *CredError) Error() string {
	if e.Message == "" {
		return e.Code
	}
	return e.Code + ": " + e.Message
}

// provider holds the single registered implementation. It is nil until an implementation registers itself in an
// init() (provider_impl.go); Start fails with a fatal error if it is still nil.
var provider CredentialProvider

// SetCredentialProvider registers the implementation, from an init() in the implementation's file:
//
//	func init() { SetCredentialProvider(&credentialProvider{}) }
//
// The last registration wins.
func SetCredentialProvider(p CredentialProvider) { provider = p }
