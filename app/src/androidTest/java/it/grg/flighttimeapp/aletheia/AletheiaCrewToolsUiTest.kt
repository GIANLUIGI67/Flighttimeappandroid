package it.grg.flighttimeapp.aletheia

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import it.grg.flighttimeapp.BuildConfig
import it.grg.flighttimeapp.R
import org.hamcrest.CoreMatchers.containsString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Crew Tools screens on an emulator with a fake server (AletheiaServiceLocator.backendOverride):
 * no Firebase, no Play, no network. CT-HUB, CT-CHAT-COMPOSE, CT-CHAT-MODE-SWITCH, CT-CHAT-NETWORK,
 * CT-PAYWALL-WAIT, CT-DELETE-MY-DATA, CT-VERSION-LABEL.
 */
@RunWith(AndroidJUnit4::class)
class AletheiaCrewToolsUiTest {

    private class UiFakeBackend : AletheiaBackend {
        val chatRequests = mutableListOf<AletheiaChatRequest>()
        val deletes = mutableListOf<String>()
        val purchases = mutableListOf<String>()
        override suspend fun setConsent(granted: Boolean, source: String): Boolean = granted
        override suspend fun getStatus(): AletheiaStatus = AletheiaStatus(false, "free", 0, 20, 20, 0, 0, 0)
        override suspend fun chat(request: AletheiaChatRequest): AletheiaChatResult {
            synchronized(chatRequests) { chatRequests += request }
            return AletheiaChatResult(
                messageId = request.clientRequestId,
                answer = "Fake online answer.",
                brand = AletheiaFeature.BRAND,
                source = AletheiaSource.ONLINE,
                route = "online",
                remainingDaily = 19,
                remainingHaiku = 0,
                creditsRemaining = 0,
                dailyUsed = 1,
                dailyLimit = 20,
                trainingConsent = false,
                engine = AletheiaEngine.ONLINE
            )
        }
        override suspend fun logTurn(turn: AletheiaTurnLog): Boolean = true
        override suspend fun submitFeedback(feedback: AletheiaFeedbackPayload): Boolean = true
        override suspend fun deleteMyData(source: String): AletheiaDeleteResult {
            synchronized(deletes) { deletes += source }
            return AletheiaDeleteResult(true, false, 3, 1, 2)
        }
        override suspend fun verifyPlayPurchase(productId: String, purchaseToken: String): AletheiaPurchaseResult {
            synchronized(purchases) { purchases += productId }
            return AletheiaPurchaseResult(false, null, null)
        }
    }

    private val fake = UiFakeBackend()
    private var networkUp = true

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences(AletheiaPrefs.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        AletheiaPrefs(ctx.getSharedPreferences(AletheiaPrefs.PREFS, Context.MODE_PRIVATE)).apply {
            consentAnswered = true
            trainingConsentGranted = false
            lowPowerWarningDismissed = true
            preferredModeOnline = true
        }
        AletheiaServiceLocator.backendOverride = fake
        AletheiaServiceLocator.networkOverride = { networkUp }
    }

    @After
    fun tearDown() {
        AletheiaServiceLocator.backendOverride = null
        AletheiaServiceLocator.networkOverride = null
    }

    private fun waitUntil(what: String, timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (cond()) return
            Thread.sleep(100)
        }
        assertTrue("timed out waiting for: $what", cond())
    }

    private fun waitForText(text: String, timeoutMs: Long = 10_000) {
        val end = SystemClock.uptimeMillis() + timeoutMs
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < end) {
            try {
                onView(withText(containsString(text))).check(matches(isDisplayed()))
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(150)
            }
        }
        throw AssertionError("text not shown: $text", last)
    }

    /** Offline reply when no model file is installed (EN or IT, depending on the emulator language). */
    /**
     * The deterministic offline notice: "model missing" on a plain APK, or, since the 17/11/2026 asset pack ships
     * the model, "on the phone but the engine could not open it" in these test builds without the native engine.
     * The first offline answer copies the 986 MB model, hence the longer wait.
     */
    private fun waitForModelMissing() {
        val end = SystemClock.uptimeMillis() + 90_000
        while (SystemClock.uptimeMillis() < end) {
            for (t in listOf(
                "not on this phone yet", "non e' ancora sul telefono",
                "could not open it", "non e' riuscito ad aprirlo"
            )) {
                try {
                    onView(withText(containsString(t))).check(matches(isDisplayed()))
                    return
                } catch (_: Throwable) {
                }
            }
            Thread.sleep(150)
        }
        throw AssertionError("offline notice not shown")
    }

    // Not covered by any study card: Online sends it to the server, Offline answers locally.
    private val freeQuestion = "Tell me about crew resource management"

    private fun send(text: String) {
        onView(withId(R.id.aletheiaInput)).perform(replaceText(text), closeSoftKeyboard())
        onView(withId(R.id.aletheiaSend)).perform(click())
    }

    @Test
    fun hub_showsVersion_andOpensChatPlansSettings() {
        ActivityScenario.launch(AletheiaHubActivity::class.java).use {
            onView(withId(R.id.aletheiaVersionLabel)).check(matches(withText(containsString(BuildConfig.ALETHEIA_VERSION))))
            onView(withId(R.id.aletheiaOpenChatCard)).perform(click())
            onView(withId(R.id.aletheiaInput)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.aletheiaPlansCard)).perform(click())
            onView(withId(R.id.aletheiaPaywallClose)).check(matches(isDisplayed()))
            pressBack()
            onView(withId(R.id.aletheiaHubSettings)).perform(click())
            onView(withId(R.id.aletheiaConsentSwitch)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun chat_online_usesServer_offline_doesNot_emptyInputSendsNothing() {
        ActivityScenario.launch(AletheiaChatActivity::class.java).use {
            onView(withId(R.id.aletheiaSend)).perform(click()) // empty field
            Thread.sleep(500)
            assertEquals(0, synchronized(fake.chatRequests) { fake.chatRequests.size })

            onView(withId(R.id.aletheiaModeOnline)).perform(click())
            send("Hello from the UI test")
            waitForText("Fake online answer.")
            assertEquals(1, synchronized(fake.chatRequests) { fake.chatRequests.size })

            onView(withId(R.id.aletheiaModeOffline)).perform(click())
            send(freeQuestion)
            waitForModelMissing()
            assertEquals("Offline must not call the server", 1, synchronized(fake.chatRequests) { fake.chatRequests.size })
            send("What is V1?")
            waitForText("V1 — Decision speed")
        }
    }

    @Test
    fun chat_onlineWithoutNetwork_answersOffline_withoutServer() {
        networkUp = false
        ActivityScenario.launch(AletheiaChatActivity::class.java).use {
            onView(withId(R.id.aletheiaModeOnline)).perform(click())
            send(freeQuestion)
            waitForModelMissing()
            assertEquals(0, synchronized(fake.chatRequests) { fake.chatRequests.size })
        }
    }

    @Test
    fun paywall_showsWaitOption_andClosesWithoutPurchase() {
        val scenario = ActivityScenario.launch(AletheiaPaywallActivity::class.java)
        onView(withText(R.string.aletheia_paywall_wait)).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withId(R.id.aletheiaPaywallClose)).perform(click())
        waitUntil("paywall closed") { scenario.state == Lifecycle.State.DESTROYED }
        assertTrue(synchronized(fake.purchases) { fake.purchases.isEmpty() })
        // Reopen: still usable, still nothing bought.
        ActivityScenario.launch(AletheiaPaywallActivity::class.java).use {
            onView(withId(R.id.aletheiaBuyPlus)).check(matches(isDisplayed()))
        }
        assertTrue(synchronized(fake.purchases) { fake.purchases.isEmpty() })
    }

    @Test
    fun settings_deleteMyData_asksConfirmation_thenCallsServerOnce() {
        ActivityScenario.launch(AletheiaSettingsActivity::class.java).use {
            onView(withId(R.id.aletheiaImportModel)).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withId(R.id.aletheiaDeleteMyData)).perform(scrollTo(), click())
            assertEquals("nothing deleted before confirming", 0, synchronized(fake.deletes) { fake.deletes.size })
            onView(withId(android.R.id.button1)).perform(click())
            waitUntil("deleteMyData called") { synchronized(fake.deletes) { fake.deletes.size } == 1 }
        }
    }
}
