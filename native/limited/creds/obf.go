package creds

import (
	"encoding/base64"
	"strings"
)

// Encoded data: base64 of UTF-8 bytes with a fixed mask. Values are decoded once, at start-up.
var dataKey = []byte{0x2b, 0x71, 0x4d, 0x9e, 0x13, 0xc7, 0x5a, 0x08}

func decode(s string) string {
	raw, err := base64.StdEncoding.DecodeString(s)
	if err != nil {
		return ""
	}
	for i := range raw {
		raw[i] ^= dataKey[i%len(dataKey)]
	}
	return string(raw)
}

func decodeList(blob string) []string {
	text := decode(blob)
	if text == "" {
		return nil
	}
	return strings.Split(text, "\n")
}
