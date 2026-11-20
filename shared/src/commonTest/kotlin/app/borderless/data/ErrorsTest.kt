package app.borderless.data

import app.borderless.core.GeoError
import app.borderless.net.HttpStatus
import app.borderless.net.NetError
import app.borderless.net.TooBig
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The network's failures are never kept as bugs, on any platform; anything else is. */
class ErrorsTest {
    @Test
    fun networkFailuresAreNotBugs() {
        assertFalse(Errors.isBug(NetError(NetError.Kind.DNS, "no address")))
        assertFalse(Errors.isBug(HttpStatus(404)))
        assertFalse(Errors.isBug(TooBig(10)))
        assertFalse(Errors.isBug(GeoError("lists", NetError(NetError.Kind.UNREACHABLE, "timeout"))))
        assertFalse(Errors.isBug(IllegalStateException("wrapped", HttpStatus(500))))
        assertTrue(Errors.isBug(IllegalStateException("a bug")))
    }
}
