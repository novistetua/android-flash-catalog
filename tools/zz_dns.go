package main

import (
	"context"
	"net"
	"os"
	"strings"
	"time"
)

// На Android нет /etc/resolv.conf: берём DNS-серверы из переменной FC_DNS (через запятую).
func init() {
	list := os.Getenv("FC_DNS")
	if list == "" {
		return
	}
	servers := strings.Split(list, ",")
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			d := net.Dialer{Timeout: 3 * time.Second}
			var last error
			for _, s := range servers {
				s = strings.TrimSpace(s)
				if s == "" {
					continue
				}
				c, err := d.DialContext(ctx, "udp", net.JoinHostPort(s, "53"))
				if err == nil {
					return c, nil
				}
				last = err
			}
			return nil, last
		},
	}
}
