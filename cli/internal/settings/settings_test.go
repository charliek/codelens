package settings

import "testing"

func TestLoad_DefaultPortRange(t *testing.T) {
	// Ensure the env overrides are absent so we observe the built-in defaults.
	t.Setenv("CODELENS_SERVER__PORT_RANGE__START", "")
	t.Setenv("CODELENS_SERVER__PORT_RANGE__END", "")

	s := Load()
	if s.PortRangeStart != 61000 {
		t.Errorf("PortRangeStart = %d, want 61000", s.PortRangeStart)
	}
	if s.PortRangeEnd != 65535 {
		t.Errorf("PortRangeEnd = %d, want 65535", s.PortRangeEnd)
	}
}

func TestLoad_PortRangeEnvOverride(t *testing.T) {
	t.Setenv("CODELENS_SERVER__PORT_RANGE__START", "50000")
	t.Setenv("CODELENS_SERVER__PORT_RANGE__END", "50500")

	s := Load()
	if s.PortRangeStart != 50000 || s.PortRangeEnd != 50500 {
		t.Errorf("port range = [%d, %d], want [50000, 50500]", s.PortRangeStart, s.PortRangeEnd)
	}
}
