package it.grg.flighttimeapp.crewl

import android.net.Uri
import android.os.Bundle
import android.graphics.ImageDecoder
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.GridLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import it.grg.flighttimeapp.R

class CrewProfileSettingsActivity : AppCompatActivity() {

    private lateinit var photoView: ImageView
    private lateinit var nicknameInput: EditText
    private lateinit var companyInput: EditText
    private lateinit var phoneInput: EditText
    private lateinit var baseSpinner: Spinner
    private lateinit var roleSpinner: Spinner
    private lateinit var visibilitySpinner: Spinner
    private lateinit var bioInput: EditText
    private lateinit var extraPhotosGrid: GridLayout
    private lateinit var excludedInput: EditText
    private lateinit var excludedAddBtn: Button
    private lateinit var excludedList: LinearLayout
    private val store = CrewLayoverStore.shared
    private var isPickingExtra = false
    private val maxExtraPhotos = 4

    private val baseCountryCodes: List<Pair<String, String>> = listOf(
        // Middle East
        "🇸🇦 Saudi Arabia (+966)" to "+966",
        "🇦🇪 UAE (+971)" to "+971",
        "🇶🇦 Qatar (+974)" to "+974",
        "🇰🇼 Kuwait (+965)" to "+965",
        "🇧🇭 Bahrain (+973)" to "+973",
        "🇴🇲 Oman (+968)" to "+968",
        "🇪🇬 Egypt (+20)" to "+20",
        // Europe
        "🇮🇹 Italy (+39)" to "+39",
        "🇬🇧 UK (+44)" to "+44",
        "🇫🇷 France (+33)" to "+33",
        "🇩🇪 Germany (+49)" to "+49",
        "🇪🇸 Spain (+34)" to "+34",
        "🇵🇹 Portugal (+351)" to "+351",
        "🇳🇱 Netherlands (+31)" to "+31",
        "🇧🇪 Belgium (+32)" to "+32",
        "🇨🇭 Switzerland (+41)" to "+41",
        "🇦🇹 Austria (+43)" to "+43",
        "🇸🇪 Sweden (+46)" to "+46",
        "🇳🇴 Norway (+47)" to "+47",
        "🇩🇰 Denmark (+45)" to "+45",
        "🇫🇮 Finland (+358)" to "+358",
        "🇮🇪 Ireland (+353)" to "+353",
        "🇬🇷 Greece (+30)" to "+30",
        "🇹🇷 Turkey (+90)" to "+90",
        "🇵🇱 Poland (+48)" to "+48",
        "🇨🇿 Czech Republic (+420)" to "+420",
        "🇭🇺 Hungary (+36)" to "+36",
        "🇷🇴 Romania (+40)" to "+40",
        // Americas
        "🇺🇸 USA (+1)" to "+1",
        // Asia
        "🇮🇳 India (+91)" to "+91",
        // Africa
        "🇲🇦 Morocco (+212)" to "+212",
        "🇹🇳 Tunisia (+216)" to "+216",
        "🇩🇿 Algeria (+213)" to "+213",
        "🇱🇾 Libya (+218)" to "+218",
        "🇿🇦 South Africa (+27)" to "+27",
        "🇳🇬 Nigeria (+234)" to "+234",
        "🇬🇭 Ghana (+233)" to "+233",
        "🇰🇪 Kenya (+254)" to "+254",
        "🇹🇿 Tanzania (+255)" to "+255",
        "🇸🇳 Senegal (+221)" to "+221",
        "🇪🇹 Ethiopia (+251)" to "+251"
    )
    private val roles = CrewRole.entries.toList()
    private val visibilityModes = CrewVisibilityMode.entries.filter { it != CrewVisibilityMode.SAME_BASE_ONLY }

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            val bmp = loadBitmap(uri) ?: return@registerForActivityResult
            if (isPickingExtra) {
                CrewPhotoLoader.shared.appendLocalProfileExtraImage(bmp)
                refreshExtraPhotos()
            } else {
                photoView.setImageBitmap(bmp)
                // Upload directly to Firebase Storage (no base64 in RTDB)
                store.uploadMyPhoto(bmp)
            }
            isPickingExtra = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crew_profile_settings)

        store.init(this)

        findViewById<ImageButton>(R.id.profileBack).setOnClickListener { finish() }
        photoView = findViewById(R.id.profilePhoto)
        CrewPhotoLoader.shared.myLocalProfileImage()?.let { photoView.setImageBitmap(it) }

        nicknameInput = findViewById(R.id.nicknameInput)
        companyInput = findViewById(R.id.companyInput)
        phoneInput = findViewById(R.id.phoneInput)
        baseSpinner = findViewById(R.id.baseSpinner)
        roleSpinner = findViewById(R.id.roleSpinner)
        visibilitySpinner = findViewById(R.id.visibilitySpinner)
        bioInput = findViewById(R.id.bioInput)
        bioInput.textDirection = android.view.View.TEXT_DIRECTION_LTR
        bioInput.layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR
        extraPhotosGrid = findViewById(R.id.extraPhotosGrid)
        excludedInput = findViewById(R.id.excludedInput)
        excludedAddBtn = findViewById(R.id.excludedAddBtn)
        excludedList = findViewById(R.id.excludedList)

        findViewById<Button>(R.id.pickPhotoBtn).setOnClickListener {
            isPickingExtra = false
            pickImage.launch("image/*")
        }
        findViewById<Button>(R.id.clearPhotoBtn).setOnClickListener {
            store.updateMyPhotoBase64("") // removes URLs from RTDB + invalidates cache
            photoView.setImageDrawable(null)
        }

        baseSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            baseCountryCodes.map { it.first }
        )
        roleSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            roles.map { getString(it.labelResId) }
        )
        visibilitySpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            visibilityModes.map { getString(it.labelResId) }
        )

        baseSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                val code = baseCountryCodes[position].second
                store.updateSettings { it.copy(baseCountryCode = code) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        roleSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                store.updateSettings { it.copy(role = roles[position]) }
                store.markRoleSet()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        visibilitySpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                store.updateSettings { it.copy(visibilityMode = visibilityModes[position]) }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        nicknameInput.addTextChangedListener(SimpleTextWatcher { text ->
            store.updateSettings { it.copy(nickname = text) }
        })
        companyInput.addTextChangedListener(SimpleTextWatcher { text ->
            store.updateSettings { it.copy(companyName = text.ifBlank { null }) }
        })
        phoneInput.addTextChangedListener(SimpleTextWatcher { text ->
            store.updateSettings { it.copy(phoneNumber = text.ifBlank { null }) }
        })
        bioInput.addTextChangedListener(SimpleTextWatcher { text ->
            store.updateSettings { it.copy(bio = text.trim().ifBlank { null }) }
        })

        excludedAddBtn.setOnClickListener {
            val code = excludedInput.text.toString().trim()
            if (code.isNotEmpty()) {
        store.updateSettings { s ->
            val list = s.excludedBaseCodes.toMutableList()
            if (!list.contains(code)) {
                list.add(code)
            }
            s.copy(excludedBaseCodes = list)
        }
                excludedInput.setText("")
                refreshExcludedList()
            }
        }

        store.settingsLive.observe(this) { s ->
            // Never call setText() while the field has focus — it resets the cursor to position 0,
            // causing the cursor to jump to the left mid-typing on RTL-locale devices.
            if (!nicknameInput.isFocused && nicknameInput.text.toString() != s.nickname)
                nicknameInput.setText(s.nickname)
            if (!companyInput.isFocused && companyInput.text.toString() != (s.companyName ?: ""))
                companyInput.setText(s.companyName ?: "")
            if (!phoneInput.isFocused && phoneInput.text.toString() != (s.phoneNumber ?: ""))
                phoneInput.setText(s.phoneNumber ?: "")
            val bioText = s.bio ?: ""
            if (!bioInput.isFocused && bioInput.text.toString() != bioText)
                bioInput.setText(bioText)

            val baseIndex = baseCountryCodes.indexOfFirst { it.second == s.baseCountryCode }
            if (baseIndex >= 0 && baseSpinner.selectedItemPosition != baseIndex) {
                baseSpinner.setSelection(baseIndex)
            }
            val roleIndex = roles.indexOfFirst { it == s.role }
            if (roleIndex >= 0 && roleSpinner.selectedItemPosition != roleIndex) {
                roleSpinner.setSelection(roleIndex)
            }
            val visIndex = visibilityModes.indexOfFirst { it == s.visibilityMode }
            if (visIndex >= 0 && visibilitySpinner.selectedItemPosition != visIndex) {
                visibilitySpinner.setSelection(visIndex)
            }
            refreshExcludedList()
            refreshExtraPhotos()
        }
    }

    private fun loadBitmap(uri: Uri): android.graphics.Bitmap? {
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            val src = ImageDecoder.createSource(contentResolver, uri)
            ImageDecoder.decodeBitmap(src) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            contentResolver.openInputStream(uri)?.use { stream ->
                CrewPhotoLoader.shared.decodeBytesToBitmap(stream.readBytes(), 1600)
            }
        }
    }

    private fun refreshExcludedList() {
        excludedList.removeAllViews()
        val codes = store.settingsLive.value?.excludedBaseCodes ?: emptyList()
        if (codes.isEmpty()) {
            val tv = TextView(this).apply {
                text = getString(R.string.cl_no_exclusions)
                setTextColor(getColor(R.color.iosHint))
                textSize = 12f
            }
            excludedList.addView(tv)
            return
        }
        codes.forEach { code ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setBackgroundResource(R.drawable.bg_ios_row_12)
                setPadding(12, 8, 12, 8)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 8
                layoutParams = lp
            }
            val label = TextView(this).apply {
                text = code
                setTextColor(getColor(R.color.iosText))
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val remove = ImageButton(this).apply {
                setImageResource(android.R.drawable.ic_delete)
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnClickListener {
                    store.updateSettings { s ->
                        val list = s.excludedBaseCodes.toMutableList()
                        list.removeAll { it == code }
                        s.copy(excludedBaseCodes = list)
                    }
                    refreshExcludedList()
                }
            }
            row.addView(label)
            row.addView(remove)
            excludedList.addView(row)
        }
    }

    private fun refreshExtraPhotos() {
        extraPhotosGrid.removeAllViews()
        val photos = CrewPhotoLoader.shared.localProfileExtraImages()
        photos.forEachIndexed { index, bmp ->
            extraPhotosGrid.addView(createPhotoTile(bmp, index))
        }
        if (photos.size < maxExtraPhotos) {
            extraPhotosGrid.addView(createAddTile())
        }
    }

    private fun createPhotoTile(bmp: android.graphics.Bitmap, index: Int): FrameLayout {
        val size = dpToPx(90)
        val frame = FrameLayout(this)
        val lp = GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }
        frame.layoutParams = lp

        val img = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bmp)
            setBackgroundResource(R.drawable.bg_ios_row_12)
        }

        val del = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(24), dpToPx(24)).apply {
                marginEnd = dpToPx(2)
                topMargin = dpToPx(2)
                gravity = android.view.Gravity.END or android.view.Gravity.TOP
            }
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setColorFilter(getColor(android.R.color.white))
            contentDescription = getString(R.string.cl_clear_photo)
            setOnClickListener {
                CrewPhotoLoader.shared.removeLocalProfileExtraImage(index)
                refreshExtraPhotos()
            }
        }

        frame.addView(img)
        frame.addView(del)
        return frame
    }

    private fun createAddTile(): FrameLayout {
        val size = dpToPx(90)
        val frame = FrameLayout(this)
        val lp = GridLayout.LayoutParams().apply {
            width = size
            height = size
            setMargins(dpToPx(4), dpToPx(4), dpToPx(4), dpToPx(4))
        }
        frame.layoutParams = lp
        frame.setBackgroundResource(R.drawable.bg_ios_row_12)

        val addBtn = ImageButton(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setImageResource(R.drawable.ic_plus_white)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = getString(R.string.cl_add_photo)
            setOnClickListener {
                isPickingExtra = true
                pickImage.launch("image/*")
            }
        }
        frame.addView(addBtn)
        return frame
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

}
