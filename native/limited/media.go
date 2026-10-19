package limited

import (
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"sync"

	"golang.org/x/crypto/chacha20poly1305"
)

// Packets between this client and the server are framed as RTP media: an RTP header followed by a ChaCha20-Poly1305
// sealed payload (the header is the AEAD's additional data) and random trailing padding. The header shape and the two
// payload types (111 audio, 96 video) are wire constants the server expects.

var aeadCache sync.Map // key string -> cipher.AEAD

func getAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("key must be %d bytes", wrapKeyLen)
	}
	if v, ok := aeadCache.Load(string(key)); ok {
		return v.(cipher.AEAD), nil
	}
	a, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	aeadCache.Store(string(key), a)
	return a, nil
}

type mediaConfig struct {
	ssrc        uint32
	payloadType uint8
	paddingMax  int
}

func newMediaConfig(mode string) *mediaConfig {
	var buf [4]byte
	rand.Read(buf[:])
	pt := uint8(111)
	pad := 24
	if mode == "video" {
		pt = 96
		pad = 60
	}
	return &mediaConfig{ssrc: binary.BigEndian.Uint32(buf[:]), payloadType: pt, paddingMax: pad}
}

type mediaState struct {
	mu      sync.Mutex
	initSeq uint16
	initTs  uint32
	count   uint64
}

func newMediaState() *mediaState {
	var buf [6]byte
	rand.Read(buf[:])
	return &mediaState{initSeq: binary.BigEndian.Uint16(buf[0:2]), initTs: binary.BigEndian.Uint32(buf[2:6])}
}

func mediaNonce(ssrc uint32, seq uint16, ts uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	binary.BigEndian.PutUint32(n[8:12], ts)
	return n
}

func mediaWrap(key, payload []byte, cfg *mediaConfig, state *mediaState) ([]byte, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(payload) == 0 {
		return nil, errors.New("empty payload")
	}

	state.mu.Lock()
	c := state.count
	state.count++
	state.mu.Unlock()

	seq := state.initSeq + uint16(c)
	ts := state.initTs + uint32(c)*960 + uint32(c>>16)
	nonce := mediaNonce(cfg.ssrc, seq, ts)

	padRand := 0
	if cfg.paddingMax > 0 {
		var r [1]byte
		rand.Read(r[:])
		padRand = int(r[0]) % cfg.paddingMax
	}
	padTotal := padRand + 1

	out := make([]byte, 12+len(payload)+chacha20poly1305.Overhead+padTotal)
	out[0] = 0x80 | 0x20 // RTP v2, padding bit
	out[1] = cfg.payloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.ssrc)

	a, err := getAEAD(key)
	if err != nil {
		return nil, fmt.Errorf("cipher init: %w", err)
	}
	sealed := a.Seal(out[12:12], nonce, payload, out[:12])

	padStart := 12 + len(sealed)
	if padRand > 0 {
		rand.Read(out[padStart : padStart+padRand])
	}
	out[len(out)-1] = byte(padTotal)
	return out, nil
}

func mediaUnwrap(key, wire, dst []byte) (int, error) {
	if len(key) != wrapKeyLen {
		return 0, fmt.Errorf("key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(wire) < 13 {
		return 0, errors.New("packet too short")
	}
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("not RTP v2")
	}

	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	end := len(wire)
	if wire[0]&0x20 != 0 { // padding bit
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > end-12 {
			return 0, fmt.Errorf("invalid padding length %d", padLen)
		}
		end -= padLen
	}

	ctLen := end - 12
	if ctLen <= chacha20poly1305.Overhead {
		return 0, errors.New("no payload after header/padding")
	}
	if ctLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("dst buffer too small")
	}

	a, err := getAEAD(key)
	if err != nil {
		return 0, fmt.Errorf("cipher init: %w", err)
	}
	plain, err := a.Open(dst[:0], mediaNonce(ssrc, seq, ts), wire[12:end], wire[:12])
	if err != nil {
		return 0, fmt.Errorf("auth: %w", err)
	}
	return len(plain), nil
}

func isRTP(wire []byte) bool {
	if len(wire) < 13 || (wire[0]>>6) != 2 {
		return false
	}
	pt := wire[1] & 0x7F
	return pt == 111 || pt == 96
}
