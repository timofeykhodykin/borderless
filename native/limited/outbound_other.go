//go:build !darwin

package limited

import "syscall"

// Only darwin binds sockets to an interface (there the tunnel's routes take everything; on Android the process
// runs outside the tunnel, where binding would be wrong). Elsewhere it does nothing.
func outboundControl(string, string, syscall.RawConn) error { return nil }
