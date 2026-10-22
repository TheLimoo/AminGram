module github.com/amingram/dns-native/soarbridge

go 1.25.0

// Wire-pinned (DNS-BUILD-PLAN.md §2.1): our server runs WhiteDNS
// Soar-Server v0.1.3, whose go.mod requires exactly this client
// version. getlantern/soar is pre-release with no compatibility
// promises — do NOT track main; bump only together with the server.
require github.com/getlantern/soar v0.2.2
