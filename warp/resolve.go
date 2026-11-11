package main

// How the helper reaches Cloudflare's WARP sign-up address (api.cloudflareclient.com) on networks that block or poison DNS.
// The address is looked for in several places at once and every address found is tried at once: the first one that completes
// a real TLS handshake for that name wins, so an answer that is poisoned, black-holed or filtered is simply skipped.
// Sources: addresses built in (Cloudflare's anycast, no lookup needed), the phone's own DNS, plain DNS to three public
// servers, and DNS over HTTPS to two of them by IP address (looks like ordinary web traffic, immune to DNS tampering).
// Every step is logged (it shows in Settings, WARP log) so a failure says what was blocked.

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// verbose: the full list of what each source found is logged on the first try only (the later tries would repeat it)
var verbose atomic.Bool

func init() { verbose.Store(true) }

const apiHost = "api.cloudflareclient.com"

// api.cloudflareclient.com sits behind Cloudflare, so any of Cloudflare's addresses answers it when the TLS name is right.
var builtinIPs = []string{"104.16.24.84", "104.16.192.82", "162.159.137.105", "162.159.138.105"}

type source struct {
	name string
	find func(ctx context.Context, host string) ([]string, error)
}

// package variables so tests can swap them
var (
	sources = []source{
		{"built-in", builtin},
		{"system DNS", systemDNS},
		{"DNS 1.1.1.1", udpDNS("1.1.1.1:53")},
		{"DNS 8.8.8.8", udpDNS("8.8.8.8:53")},
		{"DNS 9.9.9.9", udpDNS("9.9.9.9:53")},
		{"DoH 1.1.1.1", doh("https://1.1.1.1/dns-query")},
		{"DoH 8.8.8.8", doh("https://8.8.8.8/resolve")},
	}
	// HTTP/2 is offered, as the 1.1.1.1 app (and any browser) does: Cloudflare answers this sign-up with 429 over plain HTTP/1.1
	apiTLS = func(host string) *tls.Config {
		return &tls.Config{ServerName: host, NextProtos: []string{"h2", "http/1.1"}}
	}
	stagger = 300 * time.Millisecond // head start each address gets over the next one
)

// addDNS puts DNS servers (comma separated IP addresses) in front of the others: the user's own, such as their ISP's resolver.
func addDNS(list string) {
	var extra []source
	for _, ip := range strings.Split(list, ",") {
		ip = strings.TrimSpace(ip)
		if net.ParseIP(ip) != nil {
			extra = append(extra, source{"DNS " + ip + " (yours)", udpDNS(net.JoinHostPort(ip, "53"))})
		}
	}
	sources = append(extra, sources...)
}

func builtin(_ context.Context, host string) ([]string, error) {
	if host == apiHost {
		return builtinIPs, nil
	}
	return nil, nil
}

// systemDNS asks the phone's own resolver (what its browser uses), which the other sources don't depend on.
func systemDNS(ctx context.Context, host string) ([]string, error) {
	return (&net.Resolver{}).LookupHost(ctx, host)
}

func udpDNS(server string) func(context.Context, string) ([]string, error) {
	return func(ctx context.Context, host string) ([]string, error) {
		r := &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "udp", server)
		}}
		return r.LookupHost(ctx, host)
	}
}

// doh uses the JSON form of DNS over HTTPS (Cloudflare and Google both answer it on their IP address).
func doh(url string) func(context.Context, string) ([]string, error) {
	return func(ctx context.Context, host string) ([]string, error) {
		req, err := http.NewRequestWithContext(ctx, "GET", url+"?type=A&name="+host, nil)
		if err != nil {
			return nil, err
		}
		req.Header.Set("Accept", "application/dns-json")
		resp, err := (&http.Client{Transport: &http.Transport{DisableKeepAlives: true}}).Do(req)
		if err != nil {
			return nil, err
		}
		defer resp.Body.Close()
		raw, _ := io.ReadAll(io.LimitReader(resp.Body, 1<<16))
		return parseDoH(raw)
	}
}

func parseDoH(raw []byte) ([]string, error) {
	var r struct {
		Status int
		Answer []struct {
			Type int
			Data string
		}
	}
	if err := json.Unmarshal(raw, &r); err != nil {
		return nil, errors.New("not a DNS answer")
	}
	var out []string
	for _, a := range r.Answer {
		if a.Type == 1 && net.ParseIP(a.Data) != nil {
			out = append(out, a.Data)
		}
	}
	if len(out) == 0 {
		return nil, fmt.Errorf("no address (status %d)", r.Status)
	}
	return out, nil
}

// dialAPI connects (TLS included) to host:port: all sources are asked at once, each new address is dialled a moment after the
// one before it (so a healthy network opens one connection), first working one wins. Used as the HTTP client's DialTLSContext, so the name stays the TLS name and the certificate is checked.
func dialAPI(ctx context.Context, network, addr string) (net.Conn, error) {
	host, port, err := net.SplitHostPort(addr)
	if err != nil {
		return nil, err
	}
	all, cancelAll := context.WithCancel(ctx)
	defer cancelAll()

	ips := make(chan string, 64)
	var wg sync.WaitGroup
	for _, s := range sources {
		wg.Add(1)
		go func(s source) {
			defer wg.Done()
			start := time.Now()
			sctx, cancel := context.WithTimeout(all, 5*time.Second)
			defer cancel()
			found, err := s.find(sctx, host)
			if err != nil {
				if all.Err() == nil && verbose.Load() { // not just because another source already won
					log("lookup " + s.name + ": " + short(err))
				}
				return
			}
			if verbose.Load() {
				log(fmt.Sprintf("lookup %s: %s (%d ms)", s.name, strings.Join(found, " "), time.Since(start).Milliseconds()))
			}
			for _, ip := range found {
				select {
				case ips <- ip:
				case <-all.Done():
					return
				}
			}
		}(s)
	}
	go func() { wg.Wait(); close(ips) }()

	type result struct {
		conn net.Conn
		ip   string
		err  error
	}
	results := make(chan result)
	seen := map[string]bool{}
	var failures []string
	pending := 0
	for ipsOpen := true; ipsOpen || pending > 0; {
		select {
		case ip, ok := <-ips:
			if !ok {
				ips, ipsOpen = nil, false
				continue
			}
			if seen[ip] {
				continue
			}
			seen[ip] = true
			wait := time.Duration(len(seen)-1) * stagger
			pending++
			go func() {
				// One address at a time, the next only if this one is slow: a burst of connections to every address at once
				// is what a flood looks like, and all but one were thrown away
				select {
				case <-time.After(wait):
				case <-all.Done():
				}
				c, err := dialTLS(all, ip, port, host)
				select {
				case results <- result{c, ip, err}:
				case <-all.Done():
					if c != nil {
						c.Close()
					}
				}
			}()
		case r := <-results:
			pending--
			if r.err == nil {
				log("connected to " + r.ip)
				return r.conn, nil
			}
			failures = append(failures, r.ip+": "+short(r.err))
		case <-all.Done():
			return nil, fmt.Errorf("%v (%s)", all.Err(), strings.Join(failures, "; "))
		}
	}
	if len(failures) == 0 {
		return nil, errors.New("no address found for " + host + " (DNS blocked?)")
	}
	return nil, errors.New("could not connect to " + host + ": " + strings.Join(failures, "; "))
}

func dialTLS(ctx context.Context, ip, port, host string) (net.Conn, error) {
	ctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	var d net.Dialer
	raw, err := d.DialContext(ctx, "tcp", net.JoinHostPort(ip, port))
	if err != nil {
		return nil, err
	}
	c := tls.Client(raw, apiTLS(host))
	if err := c.HandshakeContext(ctx); err != nil {
		raw.Close()
		return nil, err
	}
	return c, nil
}

// short keeps the log readable: the last part of Go's nested error text ("dial tcp 1.2.3.4:443: i/o timeout" and the like).
func short(err error) string {
	s := err.Error()
	if len(s) > 110 {
		s = "..." + s[len(s)-107:]
	}
	return s
}
