package it.grg.flighttimeapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import it.grg.flighttimeapp.crewl.CrewLayoverChatStore
import it.grg.flighttimeapp.crewl.CrewSettingsActivity
import it.grg.flighttimeapp.salary.SalaryGate
import it.grg.flighttimeapp.salary.SalaryHomeActivity
import it.grg.flighttimeapp.salary.SalaryStorage
import it.grg.flighttimeapp.training.TrainingActivity

class MainActivity : AppCompatActivity() {

    private var chatStore: CrewLayoverChatStore? = null
    private var mainStarted = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val startChatObserverRunnable = Runnable {
        if (!mainStarted || isFinishing || isDestroyed) return@Runnable
        ensureChatStore().startThreadsObserver()
    }
    private lateinit var crewLayoverUnreadDot: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        crewLayoverUnreadDot = findViewById(R.id.crewLayoverUnreadDot)

        findViewById<View>(R.id.cardCalculator).setOnClickListener {
            startActivity(Intent(this, FlightTimeCalculatorActivity::class.java))
        }
        findViewById<View>(R.id.cardSalary).setOnClickListener {
            startActivity(Intent(this, SalaryHomeActivity::class.java))
        }
        findViewById<View>(R.id.cardLayover).setOnClickListener {
            startActivity(Intent(this, CrewSettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardTraining).setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }
        findViewById<View>(R.id.contactBtn).setOnClickListener {
            openFeedbackEmail()
        }
        findViewById<View>(R.id.shareBtnCard).setOnClickListener {
            shareApp()
        }
        findViewById<View>(R.id.cardPro).setOnClickListener {
            startActivity(Intent(this, SubscriptionDetailsActivity::class.java))
        }

        updateProTile()
    }

    override fun onStart() {
        super.onStart()
        mainStarted = true
        mainHandler.postDelayed(startChatObserverRunnable, 500L)
    }

    override fun onStop() {
        super.onStop()
        mainStarted = false
        mainHandler.removeCallbacks(startChatObserverRunnable)
        chatStore?.stopThreadsObserver()
    }

    private fun ensureChatStore(): CrewLayoverChatStore {
        chatStore?.let { return it }
        return CrewLayoverChatStore.shared.also { store ->
            chatStore = store
            store.unreadThreadIds.observe(this) { unread ->
                crewLayoverUnreadDot.visibility = if (unread.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun updateProTile() {
        val prefs = SalaryStorage(this).getPrefs()
        val isPro = SalaryGate.isProUser(prefs)
        val proIcon = findViewById<ImageView>(R.id.proIcon)
        val proActiveBadge = findViewById<View>(R.id.proActiveBadge)

        proIcon.setImageResource(if (isPro) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        proIcon.setColorFilter(
            ContextCompat.getColor(this, if (isPro) R.color.homeGold else R.color.homeOrange)
        )
        proActiveBadge.visibility = if (isPro) View.VISIBLE else View.GONE
    }

    private fun openFeedbackEmail() {
        val rawEmail = "innovative.aviation.gg@gmail.com"
        val email = rawEmail.replace("\\s+".toRegex(), "")
        try {
            val mailto = "mailto:$email".toUri()
            val intent = Intent(Intent.ACTION_SENDTO, mailto).apply {
                putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                putExtra(Intent.EXTRA_SUBJECT, "FlightTimeAppAndroid")
            }
            startActivity(Intent.createChooser(intent, getString(R.string.contact_me)))
        } catch (_: ActivityNotFoundException) {
            try {
                val fallback = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_SUBJECT, "FlightTimeAppAndroid")
                }
                startActivity(Intent.createChooser(fallback, getString(R.string.contact_me)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, getString(R.string.no_email_app), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun shareApp() {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.app_name))
            putExtra(Intent.EXTRA_TEXT, getString(R.string.share_app_card_text))
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_app_button)))
    }
}
