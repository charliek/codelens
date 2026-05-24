package server

import (
	"errors"
	"fmt"
	"net"
	"testing"
)

// listenOn binds a TCP listener on an OS-chosen free port on 127.0.0.1 and
// returns the listener and its port. The caller is responsible for closing it.
func listenOn(t *testing.T) (net.Listener, int) {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("could not bind a probe listener: %v", err)
	}
	return l, l.Addr().(*net.TCPAddr).Port
}

func TestAllocatePort_ReturnsPortWithinRange(t *testing.T) {
	// Anchor the range on a known-free port so the range is very likely open.
	l, base := listenOn(t)
	_ = l.Close()

	start, end := base, base+50
	if end > 65535 {
		start, end = base-50, base
	}

	for i := 0; i < 20; i++ {
		p, err := AllocatePort(start, end)
		if err != nil {
			t.Fatalf("AllocatePort(%d, %d) returned error: %v", start, end, err)
		}
		if p < start || p > end {
			t.Fatalf("port %d outside requested range [%d, %d]", p, start, end)
		}
	}
}

func TestAllocatePort_SkipsBusyPort(t *testing.T) {
	// Hold a listener open so its port is busy, then allocate over a range that
	// includes it. The result must be a different, bindable port in range.
	busy, busyPort := listenOn(t)
	defer func() { _ = busy.Close() }()

	start, end := busyPort, busyPort+40
	if end > 65535 {
		start, end = busyPort-40, busyPort
	}

	p, err := AllocatePort(start, end)
	if err != nil {
		t.Fatalf("AllocatePort(%d, %d) returned error: %v", start, end, err)
	}
	if p == busyPort {
		t.Fatalf("AllocatePort returned the busy port %d", busyPort)
	}
	if p < start || p > end {
		t.Fatalf("port %d outside requested range [%d, %d]", p, start, end)
	}
	// The returned port must actually be bindable.
	check, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", p))
	if err != nil {
		t.Fatalf("returned port %d was not bindable: %v", p, err)
	}
	_ = check.Close()
}

func TestAllocatePort_ExhaustedRangeReturnsError(t *testing.T) {
	// A single-port range whose only port is held busy must report exhaustion.
	busy, busyPort := listenOn(t)
	defer func() { _ = busy.Close() }()

	_, err := AllocatePort(busyPort, busyPort)
	if !errors.Is(err, ErrNoPortAvailable) {
		t.Fatalf("expected ErrNoPortAvailable, got %v", err)
	}
}

func TestAllocatePort_InvalidRange(t *testing.T) {
	cases := []struct{ start, end int }{
		{end: 100, start: 200},   // start > end
		{start: 0, end: 100},     // start < 1
		{start: 100, end: 70000}, // end > 65535
	}
	for _, c := range cases {
		if _, err := AllocatePort(c.start, c.end); err == nil {
			t.Fatalf("AllocatePort(%d, %d) expected an error, got nil", c.start, c.end)
		}
	}
}

func TestAllocatePort_RandomizedStart(t *testing.T) {
	// Over a wide free range, repeated allocations should not always return the
	// same (low) port, proving the start offset is randomized. Anchor on a
	// known-free port to keep the range open.
	l, base := listenOn(t)
	_ = l.Close()

	start, end := base, base+300
	if end > 65535 {
		start, end = base-300, base
	}

	seen := map[int]struct{}{}
	for i := 0; i < 40; i++ {
		p, err := AllocatePort(start, end)
		if err != nil {
			t.Fatalf("AllocatePort returned error: %v", err)
		}
		seen[p] = struct{}{}
	}
	if len(seen) < 2 {
		t.Fatalf("expected randomized ports across [%d, %d], only saw %v", start, end, seen)
	}
}
