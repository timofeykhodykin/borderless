package limited

import (
	"context"
	"errors"

	"app.borderless/limited/creds"
)

// credentialProvider is the core's source of TURN credentials: it hands the transport what the calls service issued
// for one call identifier. Caching belongs to the core (it calls Fetch at connect, near TTL expiry, and after a TURN
// authentication failure), so nothing is cached here.
type credentialProvider struct{}

func (p *credentialProvider) Fetch(ctx context.Context, callID, instance string) (*TurnCreds, error) {
	raw, err := creds.Fetch(ctx, callID, instance)
	if err != nil {
		return nil, mapFetchError(err)
	}
	return &TurnCreds{
		URLs:     raw.TurnURLs,
		Username: raw.Username,
		Password: raw.Password,
		TTL:      int64(raw.TTL),
	}, nil
}

func mapFetchError(err error) error {
	var fe *creds.FetchError
	if !errors.As(err, &fe) {
		return err
	}
	return &CredError{
		Kind:    mapFetchKind(fe.Kind),
		Code:    fe.Code,
		Message: fe.Message,
	}
}

func mapFetchKind(k creds.ErrorKind) CredErrorKind {
	switch k {
	case creds.Transient:
		return Transient
	case creds.RateLimited:
		return RateLimited
	case creds.IdentifierDead:
		return IdentifierDead
	case creds.Unavailable:
		return Unavailable
	case creds.Fatal:
		return Fatal
	}
	return Transient
}

func init() {
	SetCredentialProvider(&credentialProvider{})
}
