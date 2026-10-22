// Package soarbridge is a gomobile-bound SOCKS5 front-end for the
// Soar DNS-tunnel client (getlantern/soar v0.2.2 — wire-pinned to
// WhiteDNS Soar-Server v0.1.3; see DNS-BUILD-PLAN.md §2).
//
// Soar gives a stream dialer, not a listener. This shim owns one
// Soar client plus a local SOCKS5 server on an ephemeral 127.0.0.1
// port; Telegram's own proxy setting is pointed at that port by
// the Java side (org.telegram.messenger.amin.dns).
//
// gomobile-visible surface (only string/int/bool/error cross the
// binding), generated as org.telegram.amingram.soarbridge.Soarbridge:
//
//	Start(zone, serverKeyB64, resolversCSV string, discoveryIR bool) error
//	Stop()
//	Status() string // "idle" | "starting" | "connected" | "error: <msg>"
//	LocalPort() int
//
// Resolver assembly (system DNS first, public fallbacks) happens
// on the Java side and arrives as CSV, keeping all Android API
// access out of Go.
package soarbridge

import (
	"context"
	"crypto/ed25519"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/getlantern/soar"
)

var (
	mu       sync.Mutex
	client   *soar.Client
	listener net.Listener
	port     int
	status   = "idle"
)

func setStatus(s string) {
	mu.Lock()
	status = s
	mu.Unlock()
}

// Start creates the Soar client and opens the SOCKS5 listener.
// The Soar handshake itself is lazy (first dial), so a nil error
// means "listener up"; end-to-end liveness is proven by the Java
// side with a real SOCKS CONNECT through LocalPort.
func Start(zone, serverKeyB64, resolversCSV string, discoveryIR bool) error {
	mu.Lock()
	if listener != nil {
		mu.Unlock()
		return errors.New("soarbridge: already started")
	}
	status = "starting"
	mu.Unlock()

	fail := func(err error) error {
		setStatus("error: " + err.Error())
		return err
	}

	keyText := strings.TrimSpace(serverKeyB64)
	keyRaw, err := base64.StdEncoding.DecodeString(keyText)
	if err != nil {
		// Tolerate the URL-safe, possibly unpadded encoding the
		// soar:// link ecosystem uses.
		keyRaw, err = base64.URLEncoding.DecodeString(keyText)
		if err != nil {
			keyRaw, err = base64.RawURLEncoding.DecodeString(keyText)
			if err != nil {
				return fail(fmt.Errorf("server key is not valid base64: %w", err))
			}
		}
	}
	if len(keyRaw) != ed25519.PublicKeySize {
		return fail(fmt.Errorf("server key must be %d bytes, got %d", ed25519.PublicKeySize, len(keyRaw)))
	}

	var resolvers []string
	for _, r := range strings.Split(resolversCSV, ",") {
		if r = strings.TrimSpace(r); r != "" {
			resolvers = append(resolvers, r)
		}
	}
	if len(resolvers) == 0 {
		return fail(errors.New("soarbridge: no resolvers"))
	}

	c, err := soar.NewClient(soar.Config{
		Zone:      strings.TrimSpace(zone),
		ServerKey: ed25519.PublicKey(keyRaw),
		Resolvers: resolvers,
		Discovery: soar.Discovery{Enabled: discoveryIR, Country: "IR"},
		// TCP stays "auto" (the default): a resolver whose UDP path
		// never answers gains a TCP/53 path — Soar's answer to
		// UDP-blocked networks.
	})
	if err != nil {
		return fail(err)
	}

	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		c.Close()
		return fail(err)
	}

	mu.Lock()
	client = c
	listener = ln
	port = ln.Addr().(*net.TCPAddr).Port
	status = "connected"
	mu.Unlock()

	go acceptLoop(ln, c)
	return nil
}

// Stop closes the listener and the Soar client; in-flight relays
// die with their connections.
func Stop() {
	mu.Lock()
	ln := listener
	c := client
	listener = nil
	client = nil
	port = 0
	status = "idle"
	mu.Unlock()
	if ln != nil {
		ln.Close()
	}
	if c != nil {
		c.Close()
	}
}

func Status() string {
	mu.Lock()
	defer mu.Unlock()
	return status
}

func LocalPort() int {
	mu.Lock()
	defer mu.Unlock()
	return port
}

func acceptLoop(ln net.Listener, c *soar.Client) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go handleConn(conn, c)
	}
}

func handleConn(local net.Conn, c *soar.Client) {
	defer local.Close()
	target, err := socksHandshake(local)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	remote, err := c.DialContext(ctx, "tcp", target)
	cancel()
	if err != nil {
		writeReply(local, 0x05) // connection refused
		return
	}
	defer remote.Close()
	if err := writeReply(local, 0x00); err != nil {
		return
	}
	relay(local, remote)
}

// socksHandshake runs the server side of a minimal SOCKS5 greeting
// + CONNECT request and returns the target "host:port". Only
// no-auth and CONNECT are supported (Telegram's SOCKS5 use is
// CONNECT-only); anything else gets the standard refusal replies.
func socksHandshake(conn net.Conn) (string, error) {
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return "", err
	}
	if header[0] != 0x05 {
		return "", errors.New("soarbridge: not SOCKS5")
	}
	methods := make([]byte, int(header[1]))
	if _, err := io.ReadFull(conn, methods); err != nil {
		return "", err
	}
	noAuth := false
	for _, m := range methods {
		if m == 0x00 {
			noAuth = true
		}
	}
	if !noAuth {
		conn.Write([]byte{0x05, 0xFF})
		return "", errors.New("soarbridge: no-auth not offered")
	}
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return "", err
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil {
		return "", err
	}
	if req[0] != 0x05 {
		return "", errors.New("soarbridge: bad request version")
	}
	if req[1] != 0x01 { // not CONNECT
		writeReply(conn, 0x07) // command not supported
		return "", errors.New("soarbridge: only CONNECT is supported")
	}
	var host string
	switch req[3] {
	case 0x01: // IPv4
		buf := make([]byte, 4)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	case 0x03: // domain
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return "", err
		}
		buf := make([]byte, int(lenBuf[0]))
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = string(buf)
	case 0x04: // IPv6
		buf := make([]byte, 16)
		if _, err := io.ReadFull(conn, buf); err != nil {
			return "", err
		}
		host = net.IP(buf).String()
	default:
		writeReply(conn, 0x08) // address type not supported
		return "", errors.New("soarbridge: bad address type")
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(conn, portBuf); err != nil {
		return "", err
	}
	targetPort := int(portBuf[0])<<8 | int(portBuf[1])
	return net.JoinHostPort(host, strconv.Itoa(targetPort)), nil
}

func writeReply(conn net.Conn, rep byte) error {
	_, err := conn.Write([]byte{0x05, rep, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
	return err
}

func relay(a, b net.Conn) {
	done := make(chan struct{}, 2)
	cp := func(dst, src net.Conn) {
		io.Copy(dst, src)
		if cw, ok := dst.(interface{ CloseWrite() error }); ok {
			cw.CloseWrite()
		}
		done <- struct{}{}
	}
	go cp(a, b)
	go cp(b, a)
	<-done
	// Returning closes both conns (defers in handleConn), which
	// unblocks the surviving copy.
}
