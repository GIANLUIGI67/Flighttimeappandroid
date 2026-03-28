package it.grg.flighttimeapp.crewl

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.widget.*
import android.text.InputType
import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import it.grg.flighttimeapp.R
import java.util.Calendar
import java.util.Date

class CrewSettingsActivity : AppCompatActivity() {

    private val store = CrewLayoverStore.shared

    private lateinit var shareAppBtn: Button
    private lateinit var profileRow: LinearLayout
    private lateinit var nearMeRow: LinearLayout
    private lateinit var chatsRow: LinearLayout
    private lateinit var myLayoverInfoRow: LinearLayout
    private lateinit var communityWarning: TextView
    private lateinit var visibilitySpinner: Spinner
    private lateinit var meetingSpinner: Spinner
    private lateinit var createEventSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var eventDetailsContainer: LinearLayout
    private lateinit var eventWarning: TextView
    private lateinit var eventDateBtn: Button
    private lateinit var eventTimeBtn: Button
    private lateinit var expiresDateBtn: Button
    private lateinit var expiresTimeBtn: Button
    private lateinit var whereInput: EditText
    private lateinit var sendToAllSwitch: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var createEventBtn: Button
    private lateinit var eventsRecycler: RecyclerView
    private lateinit var eventsEmptyText: TextView

    private lateinit var eventsAdapter: CrewEventsAdapter

    private val visibilityModes = CrewVisibilityMode.entries.filter { it != CrewVisibilityMode.SAME_BASE_ONLY }
    private val meetingTypes = MeetingType.entries.toList()
    private val appUrl = "https://apps.apple.com/app/id6756632781"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_settings)

        store.init(this)
        ensureSignedInAndStart()

        findViewById<ImageButton>(R.id.settingsBack).setOnClickListener { finish() }
        shareAppBtn = findViewById(R.id.shareAppBtn)
        profileRow = findViewById(R.id.profileRow)
        nearMeRow = findViewById(R.id.nearMeRow)
        chatsRow = findViewById(R.id.chatsRow)
        myLayoverInfoRow = findViewById(R.id.myLayoverInfoRow)
        communityWarning = findViewById(R.id.communityWarning)
        visibilitySpinner = findViewById(R.id.visibilitySpinner)
        meetingSpinner = findViewById(R.id.meetingSpinner)
        createEventSwitch = findViewById(R.id.createEventSwitch)
        eventDetailsContainer = findViewById(R.id.eventDetailsContainer)
        eventWarning = findViewById(R.id.eventWarning)
        eventDateBtn = findViewById(R.id.eventDateBtn)
        eventTimeBtn = findViewById(R.id.eventTimeBtn)
        expiresDateBtn = findViewById(R.id.expiresDateBtn)
        expiresTimeBtn = findViewById(R.id.expiresTimeBtn)
        whereInput = findViewById(R.id.whereInput)
        sendToAllSwitch = findViewById(R.id.sendToAllSwitch)
        createEventBtn = findViewById(R.id.createEventBtn)
        eventsRecycler = findViewById(R.id.eventsRecycler)
        eventsEmptyText = findViewById(R.id.eventsEmptyText)

        profileRow.setOnClickListener {
            startActivity(Intent(this, CrewProfileSettingsActivity::class.java))
        }
        nearMeRow.setOnClickListener {
            startActivity(Intent(this, CrewNearbyActivity::class.java))
        }
        chatsRow.setOnClickListener {
            startActivity(Intent(this, CrewChatsActivity::class.java))
        }
        myLayoverInfoRow.setOnClickListener {
            if (hasLocationPermission()) {
                startActivity(Intent(this, MyLayoverInfoActivity::class.java))
            } else {
                showLocationRequiredDialog()
            }
        }
        shareAppBtn.setOnClickListener {
            val shareText = getString(R.string.cl_share_sheet_text)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, "$shareText\n$appUrl")
            }
            startActivity(Intent.createChooser(intent, getString(R.string.cl_share_button)))
        }

        val visibilityAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item_blue,
            visibilityModes.map { getString(it.labelResId) }
        )
        visibilityAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_blue)
        visibilitySpinner.adapter = visibilityAdapter

        val meetingAdapter = ArrayAdapter(
            this,
            R.layout.spinner_item_blue,
            meetingTypes.map { getString(it.labelResId) }
        )
        meetingAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item_blue)
        meetingSpinner.adapter = meetingAdapter

        visibilitySpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                store.updateSettings { it.copy(visibilityMode = visibilityModes[position]) }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        createEventSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.updateEventDraft { it.copy(isEnabled = isChecked) }
            eventDetailsContainer.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            createEventBtn.isEnabled = isChecked && isProfileReady()
        }

        eventDateBtn.setOnClickListener { pickDate { picked ->
            val current = store.eventDraft().dateTime
            val updated = applyDate(current, picked)
            store.updateEventDraft { it.copy(dateTime = updated) }
            updateEventDateTimeButtons(updated)
        } }

        eventTimeBtn.setOnClickListener { pickTime { picked ->
            val current = store.eventDraft().dateTime
            val updated = applyTime(current, picked)
            store.updateEventDraft { it.copy(dateTime = updated) }
            updateEventDateTimeButtons(updated)
        } }

        expiresDateBtn.setOnClickListener { pickDate { picked ->
            val current = store.eventDraft().expirationDateTime ?: Date()
            val updated = applyDate(current, picked)
            store.updateEventDraft { it.copy(expirationDateTime = updated) }
            updateExpiresDateTimeButtons(updated)
        } }

        expiresTimeBtn.setOnClickListener { pickTime { picked ->
            val current = store.eventDraft().expirationDateTime ?: Date()
            val updated = applyTime(current, picked)
            store.updateEventDraft { it.copy(expirationDateTime = updated) }
            updateExpiresDateTimeButtons(updated)
        } }

        sendToAllSwitch.setOnCheckedChangeListener { _, isChecked ->
            store.updateEventDraft { it.copy(sendToAllNearby = isChecked) }
        }

        meetingSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                store.updateEventDraft { it.copy(meetingType = meetingTypes[position]) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        createEventBtn.setOnClickListener {
            if (!createEventBtn.isEnabled) return@setOnClickListener
            store.updateEventDraft { it.copy(whereText = whereInput.text.toString()) }
            val id = store.createEvent(defaultRadiusKm = 20.0, expiresHours = 24.0)
            if (id != null) {
                Toast.makeText(this, getString(R.string.cl_event_created), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, getString(R.string.cl_auth_failed), Toast.LENGTH_SHORT).show()
            }
        }

        eventsAdapter = CrewEventsAdapter(
            emptyList(),
            emptySet(),
            onJoin = { event ->
                if (store.settingsLive.value?.eventRemindersEnabled == true) {
                    showJoinReminderDialog(event)
                } else {
                    store.joinEvent(event.id)
                }
            },
            onLeave = { event ->
                store.leaveEvent(event.id)
            },
            onOpenChat = { event ->
                val intent = Intent(this, CrewEventChatActivity::class.java).apply {
                    putExtra(CrewEventChatActivity.EXTRA_EVENT_ID, event.id)
                    putExtra(CrewEventChatActivity.EXTRA_EVENT_TITLE, event.whereText)
                }
                startActivity(intent)
            }
        )
        eventsRecycler.layoutManager = LinearLayoutManager(this)
        eventsRecycler.adapter = eventsAdapter
        attachEventSwipeToHide()

        store.settingsLive.observe(this, Observer { s ->
            val visIndex = visibilityModes.indexOfFirst { it == s.visibilityMode }
            if (visIndex >= 0 && visibilitySpinner.selectedItemPosition != visIndex) {
                visibilitySpinner.setSelection(visIndex)
            }
            updateProfileReadyUI()
        })

        store.activeEvents.observe(this, Observer { events ->
            eventsAdapter.submit(events)
            eventsEmptyText.visibility = if (events.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        })

        store.joinedEventIds.observe(this, Observer { joined ->
            eventsAdapter.setJoinedIds(joined)
        })

        val draft = store.eventDraft()
        createEventSwitch.isChecked = draft.isEnabled
        eventDetailsContainer.visibility = if (draft.isEnabled) android.view.View.VISIBLE else android.view.View.GONE
        updateEventDateTimeButtons(draft.dateTime)
        draft.expirationDateTime?.let { updateExpiresDateTimeButtons(it) }
        whereInput.setText(draft.whereText)
        sendToAllSwitch.isChecked = draft.sendToAllNearby
        val meetingIndex = meetingTypes.indexOfFirst { it == draft.meetingType }
        if (meetingIndex >= 0) meetingSpinner.setSelection(meetingIndex)
        updateProfileReadyUI()
    }

    override fun onResume() {
        super.onResume()
        updateProfileReadyUI()
    }

    private fun updateProfileReadyUI() {
        val ready = isProfileReady()

        nearMeRow.isEnabled = ready
        chatsRow.isEnabled = ready
        myLayoverInfoRow.isEnabled = ready
        nearMeRow.isClickable = ready
        chatsRow.isClickable = ready
        myLayoverInfoRow.isClickable = ready
        nearMeRow.alpha = if (ready) 1f else 0.5f
        chatsRow.alpha = if (ready) 1f else 0.5f
        myLayoverInfoRow.alpha = if (ready) 1f else 0.5f

        communityWarning.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE

        createEventSwitch.isEnabled = ready
        createEventRowState(ready)
        createEventBtn.isEnabled = ready && createEventSwitch.isChecked
        eventWarning.visibility = if (ready) android.view.View.GONE else android.view.View.VISIBLE
    }

    private fun attachEventSwipeToHide() {
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val pos = viewHolder.adapterPosition
                val event = eventsAdapter.getItem(pos)
                if (event != null) {
                    store.hideEventFromMyList(event.id)
                } else {
                    eventsAdapter.notifyItemChanged(pos)
                }
            }
        })
        helper.attachToRecyclerView(eventsRecycler)
    }

    private fun showJoinReminderDialog(event: CrewLayoverEvent) {
        val options = AlarmOption.entries.toList()
        val labels: Array<CharSequence> = options.map { getString(it.labelResId) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_event_alarm))
            .setItems(labels) { _, which ->
                val selected = options.getOrNull(which) ?: AlarmOption.NONE
                store.joinEvent(event.id, selected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun createEventRowState(profileReady: Boolean) {
        if (!profileReady) {
            createEventSwitch.isChecked = false
            eventDetailsContainer.visibility = android.view.View.GONE
        }
    }

    private fun isProfileReady(): Boolean {
        val s = store.settingsLive.value
        val nickname = s?.nickname?.trim().orEmpty()
        val hasPhoto = CrewPhotoLoader.shared.myLocalProfileImage() != null
        return nickname.isNotEmpty() && hasPhoto && s?.role != null
    }

    private fun ensureSignedInAndStart() {
        CrewAuthManager.ensureSignedIn { uid ->
            if (uid.isNullOrBlank()) {
                Toast.makeText(this, getString(R.string.cl_auth_failed), Toast.LENGTH_SHORT).show()
                return@ensureSignedIn
            }
            CrewPresenceService.shared.start(uid)
            store.start(uid)
            store.refreshNow()
            ensureLocationPermission()
        }
    }

    private fun ensureLocationPermission() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED) {
            CrewLocationManager.shared.start(this) { loc ->
                store.updateMyLocation(loc)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun showLocationRequiredDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_location_required_title))
            .setMessage(getString(R.string.cl_location_required_message))
            .setPositiveButton(getString(R.string.cl_location_open_settings)) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", packageName, null)
                }
                startActivity(intent)
            }
            .setNegativeButton(getString(R.string.cl_location_cancel), null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            val granted = grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            if (granted) {
                CrewLocationManager.shared.start(this) { loc ->
                    store.updateMyLocation(loc)
                }
            }
        }
    }

    private fun pickDate(onPicked: (Calendar) -> Unit) {
        val now = Calendar.getInstance().time
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
            hint = "DD/MM/YYYY"
            setText(formatDate(now))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_event_date_time))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                val parsed = parseDate(text)
                if (parsed != null) {
                    onPicked(parsed)
                } else {
                    Toast.makeText(this, "Invalid date", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun pickTime(onPicked: (Calendar) -> Unit) {
        val now = Calendar.getInstance().time
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
            hint = "HH:MM"
            setText(formatTime(now))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.cl_event_time_only))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val text = input.text.toString().trim()
                val parsed = parseTime(text)
                if (parsed != null) {
                    onPicked(parsed)
                } else {
                    Toast.makeText(this, "Invalid time", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun applyDate(base: Date, picked: Calendar): Date {
        val c = Calendar.getInstance()
        c.time = base
        c.set(Calendar.YEAR, picked.get(Calendar.YEAR))
        c.set(Calendar.MONTH, picked.get(Calendar.MONTH))
        c.set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
        return c.time
    }

    private fun applyTime(base: Date, picked: Calendar): Date {
        val c = Calendar.getInstance()
        c.time = base
        c.set(Calendar.HOUR_OF_DAY, picked.get(Calendar.HOUR_OF_DAY))
        c.set(Calendar.MINUTE, picked.get(Calendar.MINUTE))
        return c.time
    }

    private fun updateEventDateTimeButtons(date: Date) {
        eventDateBtn.text = formatDate(date)
        eventTimeBtn.text = formatTime(date)
    }

    private fun updateExpiresDateTimeButtons(date: Date) {
        expiresDateBtn.text = formatDate(date)
        expiresTimeBtn.text = formatTime(date)
    }

    private fun formatDate(date: Date): String {
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
        return fmt.format(date)
    }

    private fun formatTime(date: Date): String {
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return fmt.format(date)
    }

    private fun parseDate(text: String): Calendar? {
        return try {
            val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.getDefault())
            fmt.isLenient = false
            val d = fmt.parse(text) ?: return null
            Calendar.getInstance().apply { time = d }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseTime(text: String): Calendar? {
        return try {
            val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            fmt.isLenient = false
            val d = fmt.parse(text) ?: return null
            Calendar.getInstance().apply { time = d }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val REQ_LOCATION = 6001
    }
}
