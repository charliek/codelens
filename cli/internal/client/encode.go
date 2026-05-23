package client

import (
	"strings"
)

// pythonQuote replicates Python's urllib.parse.quote(value, safe="") byte for
// byte: every byte outside the RFC 3986 "unreserved" set is percent-encoded.
//
// Locked behavior from cli/src/codelens_cli/client.py:16 and the
// characterization tests in cli/tests/test_client.py — the server captures
// FQNs from a single path segment, so dots, dollar signs, slashes, etc. must
// all encode.
//
// Differs from Go's url.PathEscape (which leaves several reserved chars
// unescaped) and url.QueryEscape (which encodes spaces as '+').
func pythonQuote(s string) string {
	var b strings.Builder
	b.Grow(len(s))
	for i := 0; i < len(s); i++ {
		c := s[i]
		if isUnreserved(c) {
			b.WriteByte(c)
			continue
		}
		const hex = "0123456789ABCDEF"
		b.WriteByte('%')
		b.WriteByte(hex[c>>4])
		b.WriteByte(hex[c&0x0F])
	}
	return b.String()
}

func isUnreserved(c byte) bool {
	switch {
	case c >= 'A' && c <= 'Z':
		return true
	case c >= 'a' && c <= 'z':
		return true
	case c >= '0' && c <= '9':
		return true
	case c == '-' || c == '.' || c == '_' || c == '~':
		return true
	}
	return false
}
