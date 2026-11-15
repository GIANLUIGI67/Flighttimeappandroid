package it.grg.flighttimeapp.aletheia

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CT-BUY-SUBSCRIPTION / CT-BUY-CREDITS / CT-PURCHASE-VERIFY / CT-RESTORE (verify-before-grant). */
class AletheiaPurchaseGrantLogicTest {
    private val verifyCalls = mutableListOf<Pair<String, String>>()
    private var answer: AletheiaPurchaseResult? = AletheiaPurchaseResult(true, "plus", null)

    private fun logic(prefs: AletheiaPrefs) = AletheiaPurchaseGrantLogic(prefs) { pid, token ->
        verifyCalls += pid to token
        answer ?: throw AletheiaError.Network("down")
    }

    @Test
    fun subscription_verified_isGrantedOnce_andAcknowledged() = runBlocking {
        val prefs = AletheiaPrefs(InMemoryPrefs())
        val p = AletheiaOwnedPurchase("tok-sub-1", listOf(AletheiaProducts.PRO_MONTHLY), true)
        val first = logic(prefs).process(listOf(p), allowPlanClear = true)
        assertTrue(first.grantedSomething)
        assertEquals(AletheiaProducts.PRO_MONTHLY, first.plan)
        assertEquals(listOf("tok-sub-1"), first.acknowledgeTokens)
        assertEquals(AletheiaProducts.PRO_MONTHLY, prefs.activePlanId)
        // Restore / refresh: same token is not verified again, plan kept, still acknowledged.
        val again = logic(prefs).process(listOf(p), allowPlanClear = true)
        assertFalse(again.grantedSomething)
        assertEquals(AletheiaProducts.PRO_MONTHLY, again.plan)
        assertEquals(1, verifyCalls.size)
    }

    @Test
    fun creditPack_usesServerBalance_andIsConsumed() = runBlocking {
        val prefs = AletheiaPrefs(InMemoryPrefs()).apply { cachedCreditsRemaining = 5 }
        answer = AletheiaPurchaseResult(true, "free", 45)
        val out = logic(prefs).process(listOf(AletheiaOwnedPurchase("tok-c", listOf(AletheiaProducts.CREDITS_S), true)), true)
        assertEquals(45, out.credits)
        assertEquals(45, prefs.cachedCreditsRemaining)
        assertEquals(listOf("tok-c"), out.consumeTokens)
        assertEquals(AletheiaProducts.CREDITS_S to "tok-c", verifyCalls.single())
    }

    @Test
    fun creditPack_withoutServerBalance_falls_back_to_40_150_300() = runBlocking {
        val prefs = AletheiaPrefs(InMemoryPrefs())
        answer = AletheiaPurchaseResult(true, null, null)
        val out = logic(prefs).process(listOf(
            AletheiaOwnedPurchase("t1", listOf(AletheiaProducts.CREDITS_S), true),
            AletheiaOwnedPurchase("t2", listOf(AletheiaProducts.CREDITS_M), true),
            AletheiaOwnedPurchase("t3", listOf(AletheiaProducts.CREDITS_L), true)
        ), true)
        assertEquals(490, out.credits)
    }

    @Test
    fun notVerified_orError_grantsNothing_andTellsTheUser() = runBlocking {
        val prefs = AletheiaPrefs(InMemoryPrefs())
        answer = AletheiaPurchaseResult(false, null, null, notDeployed = true)
        val out = logic(prefs).process(listOf(AletheiaOwnedPurchase("t", listOf(AletheiaProducts.PLUS_MONTHLY), true)), false)
        assertFalse(out.grantedSomething)
        assertNull(out.plan)
        assertEquals(listOf(AletheiaBillingNotice.VERIFY_PENDING), out.notices)
        assertTrue(out.acknowledgeTokens.isEmpty())
        assertFalse(prefs.isPurchaseTokenGranted("t"))
        answer = null // network error
        val err = logic(prefs).process(listOf(AletheiaOwnedPurchase("t", listOf(AletheiaProducts.PLUS_MONTHLY), true)), false)
        assertFalse(err.grantedSomething)
    }

    @Test
    fun pendingPurchases_areIgnored_andFailedInventoryKeepsPlan() = runBlocking {
        val prefs = AletheiaPrefs(InMemoryPrefs()).apply { activePlanId = AletheiaProducts.MAX_MONTHLY }
        val pending = logic(prefs).process(listOf(AletheiaOwnedPurchase("p", listOf(AletheiaProducts.PLUS_MONTHLY), false)), allowPlanClear = false)
        assertTrue(verifyCalls.isEmpty())
        assertEquals(AletheiaProducts.MAX_MONTHLY, pending.plan)
        // Successful empty inventory = subscription expired.
        val expired = logic(prefs).process(emptyList(), allowPlanClear = true)
        assertNull(expired.plan)
        assertNull(prefs.activePlanId)
    }
}
