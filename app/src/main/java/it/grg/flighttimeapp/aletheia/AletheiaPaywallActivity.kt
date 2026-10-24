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

        val api = AletheiaApi(this)
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

        status.text = getString(
            R.string.aletheia_paywall_status_format,
            prefs.activePlanId ?: getString(R.string.aletheia_plan_free),
            prefs.cachedCreditsRemaining
        )
    }

    override fun onProductsUpdated(products: List<ProductDetails>) {
        runOnUiThread {
            status.text = getString(
                R.string.aletheia_paywall_products_loaded,
                products.size,
                AletheiaProducts.ALL_PRODUCT_IDS.size
            )
        }
    }

    override fun onPurchasesUpdated(activePlanId: String?, creditsHint: Int) {
        runOnUiThread {
            Toast.makeText(this, R.string.aletheia_purchase_continue, Toast.LENGTH_LONG).show()
            status.text = getString(
                R.string.aletheia_paywall_status_format,
                activePlanId ?: getString(R.string.aletheia_plan_free),
                creditsHint
            )
            // After purchase, continue until the next limit (product rule).
            setResult(RESULT_OK)
            finish()
        }
    }

    override fun onBillingMessage(message: String) {
        runOnUiThread {
            status.text = message
        }
    }

    override fun onDestroy() {
        billing.destroy()
        super.onDestroy()
    }
}
