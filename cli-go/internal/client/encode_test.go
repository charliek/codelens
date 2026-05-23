package client

import "testing"

func TestPythonQuote(t *testing.T) {
	cases := []struct {
		in, want string
	}{
		// Reproduces Python: urllib.parse.quote(in, safe="")
		{"com.example.UserHandler", "com.example.UserHandler"},
		{"com.example.Outer$Inner", "com.example.Outer%24Inner"},
		{"java/util/List", "java%2Futil%2FList"},
		{"a b", "a%20b"},
		{"a+b", "a%2Bb"},
		{"a&b", "a%26b"},
		{"a;b", "a%3Bb"},
		{"a,b", "a%2Cb"},
		{"a:b", "a%3Ab"},
		{"a=b", "a%3Db"},
		{"a@b", "a%40b"},
		{"a?b", "a%3Fb"},
		{"<init>", "%3Cinit%3E"},
		{"$lambda$0", "%24lambda%240"},
		// Unreserved set must pass through unchanged.
		{"A-Z._~0-9a-z", "A-Z._~0-9a-z"},
	}
	for _, c := range cases {
		t.Run(c.in, func(t *testing.T) {
			got := pythonQuote(c.in)
			if got != c.want {
				t.Errorf("pythonQuote(%q) = %q, want %q", c.in, got, c.want)
			}
		})
	}
}
