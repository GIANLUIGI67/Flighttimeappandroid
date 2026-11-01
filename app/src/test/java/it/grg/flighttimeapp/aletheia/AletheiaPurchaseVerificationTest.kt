package it.grg.flighttimeapp.aletheia

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AletheiaPurchaseVerificationTest {
    private val pid = AletheiaProducts.CREDITS_S
    private fun valid(): Map<String, Any> = mapOf(
        "ok" to true, "productId" to pid, "grantCommitted" to true, "creditsRemaining" to 50
    )

    @Test fun acceptsCommittedBalanceAndZero() {
        assertEquals(50, AletheiaPurchaseVerification.balance(valid(), pid))
        assertEquals(0, AletheiaPurchaseVerification.balance(valid() + ("creditsRemaining" to 0L), pid))
        assertEquals(50, AletheiaPurchaseVerification.balance(valid() + ("creditsRemaining" to 50.0), pid))
    }

    @Test fun rejectsLegacyMalformedAndUncommittedResponses() {
        for (field in valid().keys) assertNull(AletheiaPurchaseVerification.balance(valid() - field, pid))
        for (bad in listOf<Any>(false, "true", 1)) {
            assertNull(AletheiaPurchaseVerification.balance(valid() + ("ok" to bad), pid))
            assertNull(AletheiaPurchaseVerification.balance(valid() + ("grantCommitted" to bad), pid))
        }
        assertNull(AletheiaPurchaseVerification.balance(emptyMap(), pid))
    }

    @Test fun rejectsWrongProductAndInvalidBalances() {
        assertNull(AletheiaPurchaseVerification.balance(valid(), AletheiaProducts.CREDITS_M))
        assertNull(AletheiaPurchaseVerification.balance(valid() + ("productId" to "unknown"), "unknown"))
        for (bad in listOf<Any>(-1, 0.5, "50", true, Double.NaN, Double.POSITIVE_INFINITY, 2147483648L)) {
            assertNull(AletheiaPurchaseVerification.balance(valid() + ("creditsRemaining" to bad), pid))
        }
    }

    @Test fun replayReturnsAbsoluteBalanceWithoutAddition() {
        var balance = 0
        repeat(10) { balance = AletheiaPurchaseVerification.balance(valid(), pid)!! }
        assertEquals(50, balance)
    }
}
