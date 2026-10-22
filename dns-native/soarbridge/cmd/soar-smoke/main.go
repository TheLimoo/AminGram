// soar-smoke is the interop smoke for the Soar bridge
// (DNS-BUILD-PLAN.md §6.3 gate 2): it starts the shim against the
// LIVE Soar server and proves the authenticated handshake + data
// path before any APK exists.
//
// Environment:
//   SOAR_ZONE       tunnel zone           (default s.limoo.indevs.in)
//   SOAR_KEY_B64    server public key, base64 (required — pass from
//                   CI secrets/vars or a local env; never commit it
//                   to a workflow file)
//   SOAR_RESOLVERS  CSV of host:port      (default 8.8.8.8:53,1.1.1.1:53)
//   SMOKE_TARGET    host:port to CONNECT through the tunnel
//                   (default 91.108.56.130:443 — a Telegram DC)
//   SMOKE_HTTPS     if set to a host name (e.g. example.com), a
//                   second CONNECT to <host>:443 is made and a TLS
//                   handshake + GET / must return an HTTP 200.
//
// Exit code 0 = PASS, 1 = FAIL.
package main

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/amingram/dns-native/soarbridge"
)

func envOr(key, def string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return def
}

func fail(format string, args ...interface{}) {
	fmt.Printf("SMOKE FAIL: "+format+"\n", args...)
	os.Exit(1)
}

func main() {
	zone := envOr("SOAR_ZONE", "s.limoo.indevs.in")
	key := strings.TrimSpace(os.Getenv("SOAR_KEY_B64"))
	if key == "" {
		fail("SOAR_KEY_B64 is not set")
	}
	resolvers := envOr("SOAR_RESOLVERS", "8.8.8.8:53,1.1.1.1:53")
	target := envOr("SMOKE_TARGET", "91.108.56.130:443")

	if err := soarbridge.Start(zone, key, resolvers, true); err != nil {
		fail("shim start: %v", err)
	}
	defer soarbridge.Stop()

	port := 0
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		if port = soarbridge.LocalPort(); port > 0 {
			break
		}
		time.Sleep(200 * time.Millisecond)
	}
	if port <= 0 {
		fail("shim did not open a local port (status=%s)", soarbridge.Status())
	}
	fmt.Printf("shim up: 127.0.0.1:%d status=%s\n", port, soarbridge.Status())

	conn, err := socksConnect(port, target, 30*time.Second)
	if err != nil {
		fail("SOCKS CONNECT %s through tunnel: %v", target, err)
	}
	fmt.Printf("SOCKS CONNECT %s through tunnel: OK\n", target)
	conn.Close()

	if httpsHost := strings.TrimSpace(os.Getenv("SMOKE_HTTPS")); httpsHost != "" {
		raw, err := socksConnect(port, net.JoinHostPort(httpsHost, "443"), 30*time.Second)
		if err != nil {
			fail("SOCKS CONNECT %s:443 through tunnel: %v", httpsHost, err)
		}
		tlsConn := tls.Client(raw, &tls.Config{ServerName: httpsHost})
		if err := tlsConn.Handshake(); err != nil {
			fail("TLS handshake with %s: %v", httpsHost, err)
		}
		fmt.Fprintf(tlsConn, "GET / HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n", httpsHost)
		reader := bufio.NewReader(tlsConn)
		line, err := reader.ReadString('\n')
		if err != nil {
			fail("reading HTTPS response: %v", err)
		}
		fmt.Printf("HTTPS response: %s", line)
		if !strings.Contains(line, " 200") {
			fail("expected HTTP 200, got: %s", strings.TrimSpace(line))
		}
		tlsConn.Close()
	}
	fmt.Println("SMOKE PASS")
}

// socksConnect is a minimal stdlib SOCKS5 client (no-auth, CONNECT).
func socksConnect(localPort int, target string, timeout time.Duration) (net.Conn, error) {
	host, portText, err := net.SplitHostPort(target)
	if err != nil {
		return nil, err
	}
	targetPort, err := strconv.Atoi(portText)
	if err != nil {
		return nil, err
	}
	conn, err := net.DialTimeout("tcp", net.JoinHostPort("127.0.0.1", strconv.Itoa(localPort)), timeout)
	if err != nil {
		return nil, err
	}
	conn.SetDeadline(time.Now().Add(timeout))
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		conn.Close()
		return nil, err
	}
	greeting := make([]byte, 2)
	if _, err := io.ReadFull(conn, greeting); err != nil {
		conn.Close()
		return nil, err
	}
	if greeting[0] != 0x05 || greeting[1] != 0x00 {
		conn.Close()
		return nil, fmt.Errorf("bad greeting reply %v", greeting)
	}
	var req []byte
	if ip := net.ParseIP(host); ip != nil && ip.To4() != nil {
		req = []byte{0x05, 0x01, 0x00, 0x01}
		req = append(req, ip.To4()...)
	} else {
		req = []byte{0x05, 0x01, 0x00, 0x03, byte(len(host))}
		req = append(req, host...)
	}
	req = append(req, byte(targetPort>>8), byte(targetPort))
	if _, err := conn.Write(req); err != nil {
		conn.Close()
		return nil, err
	}
	head := make([]byte, 4)
	if _, err := io.ReadFull(conn, head); err != nil {
		conn.Close()
		return nil, err
	}
	if head[0] != 0x05 || head[1] != 0x00 {
		conn.Close()
		return nil, fmt.Errorf("CONNECT refused, rep=%d", head[1])
	}
	var rest int
	switch head[3] {
	case 0x01:
		rest = 4 + 2
	case 0x04:
		rest = 16 + 2
	case 0x03:
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			conn.Close()
			return nil, err
		}
		rest = int(lenBuf[0]) + 2
	default:
		conn.Close()
		return nil, fmt.Errorf("bad reply address type %d", head[3])
	}
	if _, err := io.ReadFull(conn, make([]byte, rest)); err != nil {
		conn.Close()
		return nil, err
	}
	conn.SetDeadline(time.Time{})
	return conn, nil
}
