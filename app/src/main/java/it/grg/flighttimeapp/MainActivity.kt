package it.grg.flighttimeapp

import android.os.Bundle
import android.content.ActivityNotFoundException
import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.materialswitch.MaterialSwitch
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import android.content.Intent
import it.grg.flighttimeapp.salary.SalaryHomeActivity
import it.grg.flighttimeapp.training.TrainingActivity
import it.grg.flighttimeapp.crewl.CrewSettingsActivity
import it.grg.flighttimeapp.crewl.CrewLayoverChatStore

class MainActivity : AppCompatActivity() {

    private lateinit var totalText: TextView
    private lateinit var emptyText: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var negativeSwitch: MaterialSwitch

    private lateinit var hhInput: EditText
    private lateinit var mmInput: EditText
    private lateinit var noteInput: EditText

    private lateinit var addBtn: ImageButton
    private lateinit var subtractBtn: ImageButton

    private val flights = mutableListOf<FlightEntry>()
    private lateinit var adapter: FlightsAdapter

    private var allowNegativeTotals = false
    private val chatStore = CrewLayoverChatStore.shared
    private lateinit var crewLayoverUnreadDot: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        totalText = findViewById(R.id.totalText)
        emptyText = findViewById(R.id.emptyText)
        recycler = findViewById(R.id.flightsRecycler)
        negativeSwitch = findViewById(R.id.negativeSwitch)

        hhInput = findViewById(R.id.hhInput)
        mmInput = findViewById(R.id.mmInput)
        noteInput = findViewById(R.id.noteInput)

        addBtn = findViewById(R.id.addBtn)
        subtractBtn = findViewById(R.id.subtractBtn)
        crewLayoverUnreadDot = findViewById(R.id.crewLayoverUnreadDot)

        // Setup cards
        findViewById<View>(R.id.cardSalary).setOnClickListener {
            startActivity(Intent(this, SalaryHomeActivity::class.java))
        }
        findViewById<View>(R.id.cardLayover).setOnClickListener {
            startActivity(Intent(this, CrewSettingsActivity::class.java))
        }
        findViewById<View>(R.id.cardTraining).setOnClickListener {
            startActivity(Intent(this, TrainingActivity::class.java))
        }

        // Setup links
        findViewById<View>(R.id.upgradeLink).setOnClickListener {
            Toast.makeText(this, "Upgrade", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.detailsLink).setOnClickListener {
            startActivity(Intent(this, SubscriptionDetailsActivity::class.java))
        }
        findViewById<View>(R.id.restoreLink).setOnClickListener {
            Toast.makeText(this, "Restore", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.contactBtn).setOnClickListener {
            val rawEmail = "innovative.aviation.gg@gmail.com"
            val email = rawEmail.replace("\\s+".toRegex(), "")
            try {
                val mailto = Uri.parse("mailto:$email")
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
        findViewById<View>(R.id.shareBtnCard).setOnClickListener {
            Toast.makeText(this, "Share", Toast.LENGTH_SHORT).show()
        }

        adapter = FlightsAdapter(flights) { position ->
            flights.removeAt(position)
            adapter.notifyItemRemoved(position)
            updateTotal()
            updateEmptyState()
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        negativeSwitch.setOnCheckedChangeListener { _, isChecked ->
            allowNegativeTotals = isChecked
            updateTotal()
        }

        addBtn.setOnClickListener { addFlight(true) }
        subtractBtn.setOnClickListener { addFlight(false) }

        updateTotal()
        updateEmptyState()

        chatStore.unreadThreadIds.observe(this) { unread ->
            crewLayoverUnreadDot.visibility = if (unread.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        chatStore.startThreadsObserver()
    }

    override fun onStop() {
        super.onStop()
        chatStore.stopThreadsObserver()
    }

    private fun addFlight(isPositive: Boolean) {
        val hoursStr = hhInput.text.toString().trim()
        val minsStr = mmInput.text.toString().trim()

        val hours = hoursStr.toIntOrNull() ?: 0
        val minutes = minsStr.toIntOrNull() ?: 0

        if (hours == 0 && minutes == 0) {
            Toast.makeText(this, getString(R.string.enter_valid_time), Toast.LENGTH_SHORT).show()
            return
        }

        var totalMinutes = hours * 60 + minutes
        if (!isPositive) totalMinutes = -totalMinutes

        val note = noteInput.text.toString().trim()

        flights.add(0, FlightEntry(totalMinutes, note))
        adapter.notifyItemInserted(0)
        recycler.scrollToPosition(0)

        hhInput.text.clear()
        mmInput.text.clear()
        noteInput.text.clear()

        updateTotal()
        updateEmptyState()
    }

    private fun updateTotal() {
        var totalMinutes = flights.sumOf { it.minutes }

        if (!allowNegativeTotals) {
            val day = 24 * 60
            if (totalMinutes < 0) {
                totalMinutes = (totalMinutes % day + day) % day
            } else {
                totalMinutes %= day
            }
        }

        val absoluteMinutes = abs(totalMinutes)
        val hours = absoluteMinutes / 60
        val mins = absoluteMinutes % 60

        val sign = if (allowNegativeTotals && totalMinutes < 0) "-" else ""
        totalText.text = getString(R.string.total_time_format, sign, hours, mins)
    }

    private fun updateEmptyState() {
        if (flights.isEmpty()) {
            emptyText.visibility = View.VISIBLE
            recycler.visibility = View.GONE
        } else {
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

}
