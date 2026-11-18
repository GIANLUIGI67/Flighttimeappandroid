package it.grg.flighttimeapp.aletheia

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Founder 17/11/2026: the Play build ships Aletheia 1.5B; a fresh install gets a verified model with no import. */
@RunWith(AndroidJUnit4::class)
class AletheiaBundledModelTest {
    @Test
    fun freshInstall_installsTheBundledModel_verified() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val store = AletheiaLocalModelStore.fromContext(ctx)
        store.removeInstalled()
        val record = AletheiaOfflineEngine.fromContext(ctx).installBundledModel()
        assertNotNull("bundled model not found in the app", record)
        assertTrue("bundled model failed the SHA-256 check", record!!.verified)
        assertEquals(AletheiaLocalModelStore.ALETHEIA_15B_SIZE, record.sizeBytes)
        assertTrue(store.isInstalled())
    }
}
