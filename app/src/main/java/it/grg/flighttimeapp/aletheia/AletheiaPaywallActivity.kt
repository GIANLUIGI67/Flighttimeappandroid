package it.grg.flighttimeapp.aletheia

import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.android.billingclient.api.ProductDetails
import com.google.android.material.button.MaterialButton
import it.grg.flighttimeapp.R

class AletheiaPaywallActivity : AppCompatActivity(), AletheiaBillingManager.Listener {
    private lateinit var billing: AletheiaBillingManager
    private lateinit var status: TextView
    private lateinit var prefs: AletheiaPrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aletheia_paywall)
        prefs = AletheiaPrefs(this)
        status = findViewById(R.id.aletheiaPaywallStatus)

        findViewById<ImageButton>(R.id.aletheiaPaywallClose).setOnClickListener { finish() }

        val api = AletheiaServiceLocator.backend(this)
        billing = AletheiaBillingManager(this, prefs, api, lifecycleScope)
        billing.listener = this
        billing.start()

        findViewById<MaterialButton>(R.id.aletheiaBuyPlus).setOnClickListener {
            billing.launchPurchase(this, AletheiaProducts.PLUS_MONTHLY)
        }
        findViewById<MaterialButton>(R.id.aletheiaBuyPro).setOnClickListener {
            billing.launchPurchase(this, AletheiaProducts.PRO_MONTHLY)
        }
        findViewById<MaterialButton>(R.id.aletheiaBuyMax).setOnClickListener {
            billing.launchPurchase(this, AletheiaProducts.MAX_MONTHLY)
        }
        findViewById<MaterialButton>(R.id.aletheiaBuyCreditsS).setOnClickListener {
            billing.launchPurchase(this, AletheiaProducts.CREDITS_S)
        }
        findViewById<MaterialButton>(R.id.aletheiaBuyCreditsM).setOnClickListener {
            billing.launchPurchase(this, AletheiaProducts.CREDITS_M)
        }
        findViewById<MaterialButton>(R.id.aletheiaBuyCreditsL).setOnClickListener {
            billing.launchPurchase(this, AletheiaProducts.CREDITS_L)
        }

        showPlanStatus(prefs.activePlanId, prefs.cachedCreditsRemaining)
    }

    /** Plan name from the server status when known, else from the Play subscription id. */
    private fun showPlanStatus(activePlanId: String?, credits: Int) {
        val plan = AletheiaProducts.planIdFor(activePlanId)
            ?: prefs.serverPlan.takeIf { prefs.statusFromServer }
        val name = getString(
            when (AletheiaUiRules.planName(plan)) {
                AletheiaUiRules.PlanName.FREE -> R.string.aletheia_plan_name_free
                AletheiaUiRules.PlanName.PLUS -> R.string.aletheia_plan_name_plus
                AletheiaUiRules.PlanName.PRO -> R.string.aletheia_plan_name_pro
                AletheiaUiRules.PlanName.MAX -> R.string.aletheia_plan_name_max
            }
        )
        status.text = getString(R.string.aletheia_paywall_status_format, name, credits)
    }

    override fun onProductsUpdated(products: List<ProductDetails>) {
        runOnUiThread {
            if (products.size < AletheiaProducts.ALL_PRODUCT_IDS.size) {
                status.setText(R.string.aletheia_paywall_products_partial)
            } else {
                showPlanStatus(prefs.activePlanId, prefs.cachedCreditsRemaining)
            }
        }
    }

    override fun onPurchasesRestored(activePlanId: String?, creditsHint: Int) {
        runOnUiThread { showPlanStatus(activePlanId, creditsHint) }
    }

    override fun onPurchaseCompleted(activePlanId: String?, creditsHint: Int) {
        runOnUiThread {
            Toast.makeText(this, R.string.aletheia_purchase_continue, Toast.LENGTH_LONG).show()
            showPlanStatus(activePlanId, creditsHint)
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onBillingNotice(notice: AletheiaBillingNotice, detail: String?) {
        runOnUiThread { status.text = noticeText(notice, detail) }
    }

    private fun noticeText(notice: AletheiaBillingNotice, detail: String?): String = when (notice) {
        AletheiaBillingNotice.PRODUCT_UNAVAILABLE -> getString(R.string.aletheia_billing_product_unavailable)
        AletheiaBillingNotice.RECONNECTING -> getString(R.string.aletheia_billing_reconnecting)
        AletheiaBillingNotice.LAUNCH_FAILED -> getString(R.string.aletheia_billing_launch_failed, detail.orEmpty())
        AletheiaBillingNotice.VERIFY_PENDING -> getString(R.string.aletheia_billing_verify_pending)
        AletheiaBillingNotice.SUBS_STATUS_UNAVAILABLE -> getString(R.string.aletheia_billing_subs_unavailable)
        AletheiaBillingNotice.DISCONNECTED -> getString(R.string.aletheia_billing_disconnected)
        AletheiaBillingNotice.BILLING_ERROR -> getString(R.string.aletheia_billing_error, detail.orEmpty())
    }

    override fun onDestroy() {
        billing.destroy()
        super.onDestroy()
    }
}
