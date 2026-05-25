package server

import (
	"errors"
	"fmt"
	"math/rand/v2"
	"net"
)

// ErrNoPortAvailable indicates the entire configured port range was busy.
var ErrNoPortAvailable = errors.New("no available ports in range")

// AllocatePort finds a free TCP port within [start, end] on 127.0.0.1, closes
// the probe listener, and returns the port.
//
// It begins scanning at a random offset within the range and wraps around,
// rather than always starting at `start`. This spreads concurrently started
// servers across the range instead of clustering them at the low end, and makes
// it unlikely that a just-freed port is immediately re-grabbed. The TOCTOU gap
// between closing the probe and the server rebinding is unavoidable; the spawn
// loop retries with another port on collision.
//
// (The original Go port mirrored Python's sequential server_service.py scan;
// the randomized start is a deliberate divergence.)
func AllocatePort(start, end int) (int, error) {
	if start < 1 || end > 65535 || start > end {
		return 0, fmt.Errorf("invalid port range [%d, %d]", start, end)
	}
	span := end - start + 1
	offset := rand.IntN(span)
	for i := 0; i < span; i++ {
		p := start + (offset+i)%span
		l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
		if err == nil {
			_ = l.Close()
			return p, nil
		}
	}
	return 0, ErrNoPortAvailable
}
