package server

import (
	"errors"
	"fmt"
	"net"
)

// ErrNoPortAvailable indicates the entire configured port range was busy.
var ErrNoPortAvailable = errors.New("no available ports in range")

// AllocatePort tries each port in [start, end] until it can bind, then
// closes the listener and returns the port. Mirrors Python
// server_service.py:83-96 — TOCTOU race is unavoidable but the spawn loop
// retries with the next port on collision.
func AllocatePort(start, end int) (int, error) {
	for p := start; p <= end; p++ {
		l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
		if err == nil {
			_ = l.Close()
			return p, nil
		}
	}
	return 0, ErrNoPortAvailable
}
