"""Source regression checks only; not a substitute for Android or backend tests."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / 'app/src/main/java/it/grg/flighttimeapp/aletheia'


class BillingSourceSafety(unittest.TestCase):
    def setUp(self):
        self.manager = (SRC / 'AletheiaBillingManager.kt').read_text()
        self.api = (SRC / 'AletheiaApi.kt').read_text()

    def test_release_is_off(self):
        gradle = (ROOT / 'app/build.gradle.kts').read_text()
        release = gradle.split('    buildTypes {', 1)[1].split('        release {', 1)[1]
        self.assertIn('buildConfigField("boolean", "ALETHEIA_ENABLED", "false")', release)
        default = gradle.split('    defaultConfig {', 1)[1].split('    buildTypes {', 1)[0]
        self.assertIn('buildConfigField("boolean", "ALETHEIA_ENABLED", "false")', default)

    def test_checkout_honors_flag(self):
        for method in ('fun start()', 'fun launchPurchase('):
            body = self.manager.split(method, 1)[1].split('\n    }', 1)[0]
            self.assertIn('if (!BuildConfig.ALETHEIA_ENABLED) return', body)

    def test_pending_unknown_and_blank_are_skipped(self):
        body = self.manager.split('for (purchase in purchases)', 1)[1]
        verify = body.index('api.verifyPlayPurchase')
        for guard in ('purchase.purchaseState != Purchase.PurchaseState.PURCHASED',
                      'if (token.isBlank()) continue',
                      'if (pid !in AletheiaProducts.ALL_PRODUCT_IDS) continue'):
            self.assertLess(body.index(guard), verify)

    def test_failure_cannot_reach_fulfillment(self):
        body = self.manager.split('if (verifiedBalance == null) {', 1)[1]
        rejected, accepted = body.split('// Reverify', 1)
        self.assertIn('continue', rejected)
        self.assertNotIn('acknowledge(purchase)', rejected)
        self.assertNotIn('consume(purchase)', rejected)
        self.assertIn('credits = verifiedBalance', accepted)
        self.assertIn('acknowledge(purchase)', accepted)
        self.assertIn('consume(purchase)', accepted)

    def test_no_local_credit_increment_or_token_bypass(self):
        self.assertNotRegex(self.manager, r'credits\s*\+=')
        self.assertNotIn('creditsForPack', self.manager)
        self.assertNotIn('isPurchaseTokenGranted', self.manager)
        self.assertNotIn('markPurchaseTokenGranted', self.manager)
        prefs = (SRC / 'AletheiaPrefs.kt').read_text()
        self.assertNotIn('putStringSet', prefs)
        self.assertIn('remove("granted_purchase_tokens")', prefs)

    def test_response_is_strict_and_absolute(self):
        policy = (SRC / 'AletheiaPurchaseVerification.kt').read_text()
        for invariant in ('response["ok"] != true', 'response["productId"] != productId',
                          'response["grantCommitted"] != true', 'number.isFinite()',
                          'number < 0', 'number > Int.MAX_VALUE',
                          'number != number.toInt().toDouble()'):
            self.assertIn(invariant, policy)
        self.assertIn('AletheiaPurchaseVerification.balance(result, productId)', self.api)
        self.assertRegex(self.api, r'NOT_FOUND[\s\S]*?null')


if __name__ == '__main__':
    unittest.main(verbosity=2)
