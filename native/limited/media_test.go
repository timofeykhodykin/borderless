package limited

import (
	"bytes"
	"testing"
)

func TestMediaRoundTrip(t *testing.T) {
	key, err := deriveWrapKey("a-connection-password")
	if err != nil {
		t.Fatal(err)
	}
	cfg := newMediaConfig("audio")
	state := newMediaState()

	for _, payload := range [][]byte{
		[]byte("hello"),
		bytes.Repeat([]byte{0xAB}, 1300),
		{0x00},
	} {
		wire, err := mediaWrap(key, payload, cfg, state)
		if err != nil {
			t.Fatalf("wrap: %v", err)
		}
		if !isRTP(wire) {
			t.Fatalf("wrapped packet does not look like RTP")
		}
		dst := make([]byte, 2048)
		n, err := mediaUnwrap(key, wire, dst)
		if err != nil {
			t.Fatalf("unwrap: %v", err)
		}
		if !bytes.Equal(dst[:n], payload) {
			t.Fatalf("round trip mismatch: got %x want %x", dst[:n], payload)
		}
	}
}

func TestMediaWrongKeyFails(t *testing.T) {
	k1, _ := deriveWrapKey("pw-one")
	k2, _ := deriveWrapKey("pw-two")
	wire, err := mediaWrap(k1, []byte("secret"), newMediaConfig("audio"), newMediaState())
	if err != nil {
		t.Fatal(err)
	}
	if _, err := mediaUnwrap(k2, wire, make([]byte, 2048)); err == nil {
		t.Fatal("unwrap with the wrong key should fail")
	}
}

func TestDeriveWrapKeyStableAndSized(t *testing.T) {
	a, err := deriveWrapKey("same")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := deriveWrapKey("same")
	if !bytes.Equal(a, b) {
		t.Fatal("key derivation is not deterministic")
	}
	if len(a) != wrapKeyLen {
		t.Fatalf("key length %d, want %d", len(a), wrapKeyLen)
	}
	if _, err := deriveWrapKey(""); err == nil {
		t.Fatal("empty password should error")
	}
}
