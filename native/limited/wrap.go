package limited

import (
	"crypto/sha256"
	"errors"
	"fmt"
	"io"

	"golang.org/x/crypto/hkdf"
)

// wrapKeyLen is the length of the framing key.
const wrapKeyLen = 32

// deriveWrapKey turns the server connection password into the key used to frame packets as RTP (see media.go).
//
// The salt and info strings below are a wire constant shared with the server: the server derives the same key the
// same way, so they must stay byte-for-byte identical or the DTLS payloads will not authenticate. They are not a
// name shown anywhere in the app.
func deriveWrapKey(password string) ([]byte, error) {
	if password == "" {
		return nil, errors.New("empty connection password")
	}
	key := make([]byte, wrapKeyLen)
	r := hkdf.New(sha256.New, []byte(password), []byte("WDTT-WRAP-v1"), []byte("rtp-obfs/chacha20poly1305"))
	if _, err := io.ReadFull(r, key); err != nil {
		return nil, fmt.Errorf("derive key: %w", err)
	}
	return key, nil
}
