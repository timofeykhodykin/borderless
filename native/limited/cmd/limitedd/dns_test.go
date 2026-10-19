package main

import (
	"reflect"
	"testing"
)

func TestDNSServers(t *testing.T) {
	cases := []struct {
		flag   string
		resolv bool
		want   []string
	}{
		{"192.0.2.1, 2001:db8::53", false, []string{"192.0.2.1:53", "[2001:db8::53]:53"}},
		{"[2001:db8::1],not-an-ip", true, []string{"[2001:db8::1]:53"}},
		{"", true, nil},
		{"", false, []string{"1.1.1.1:53", "8.8.8.8:53"}},
		{"garbage", false, []string{"1.1.1.1:53", "8.8.8.8:53"}},
	}
	for _, c := range cases {
		if got := dnsServers(c.flag, c.resolv); !reflect.DeepEqual(got, c.want) {
			t.Errorf("dnsServers(%q, %v) = %v, want %v", c.flag, c.resolv, got, c.want)
		}
	}
}
