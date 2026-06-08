package it.grg.flighttimeapp.crewl

import android.Manifest
import android.app.AlertDialog
import android.app.Dialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Observer
import com.google.android.material.button.MaterialButton
import it.grg.flighttimeapp.R
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class CrewForumActivity : AppCompatActivity() {
    private val store = CrewSocialStore.shared
    private val layoverStore = CrewLayoverStore.shared
    private val io = Executors.newFixedThreadPool(3)

    private lateinit var root: LinearLayout
    private lateinit var feedContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var weatherText: TextView
    private lateinit var weatherIcon: TextView
    private lateinit var progress: ProgressBar

    private var currentFilter = Filter.ALL
    private var allPosts: List<CrewSocialPost> = emptyList()
    private var activeCrewIds: Set<String> = emptySet()
    private var composerDialog: Dialog? = null
    private var composerImageUri: Uri? = null
    private var composerVideoUri: Uri? = null
    private var composerPreview: TextView? = null
    private var composerLocation: String? = null
    private var composerLocationView: TextView? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            composerImageUri = uri
            composerVideoUri = null
            composerPreview?.text = getString(R.string.cl_social_photo)
        }
    }

    private val pickVideo = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            composerVideoUri = uri
            composerImageUri = null
            composerPreview?.text = getString(R.string.cl_social_video_selected)
        }
    }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startLocationAndWeather()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layoverStore.init(this)
        buildUi()
        bindStore()
        startLocationAndWeather()
        store.loadPosts(true)
    }

    private fun buildUi() {
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.WHITE) }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(82))
        }
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        frame.addView(scroll, ViewGroup.LayoutParams(-1, -1))

        root.addView(heroView(), LinearLayout.LayoutParams(-1, dp(250)).apply { setMargins(-dp(18), 0, -dp(18), dp(12)) })
        root.addView(filterRow(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        root.addView(searchRow(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        root.addView(addMediaRow(), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        progress = ProgressBar(this).apply { visibility = View.GONE }
        feedContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(feedContainer, LinearLayout.LayoutParams(-1, -2))
        root.addView(progress, LinearLayout.LayoutParams(dp(36), dp(36)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(12) })

        frame.addView(bottomBar(), FrameLayout.LayoutParams(-1, dp(72), Gravity.BOTTOM))
        setContentView(frame)
    }

    private fun heroView(): View {
        return FrameLayout(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(2, 18, 42), Color.rgb(255, 106, 0), Color.rgb(255, 178, 26))).apply { cornerRadius = 0f }
            val dark = View(context).apply { setBackgroundColor(Color.argb(82, 0, 0, 0)) }
            addView(dark, FrameLayout.LayoutParams(-1, -1))

            val back = ImageButton(context).apply {
                setImageResource(R.drawable.ic_back)
                setColorFilter(Color.WHITE)
                background = oval(Color.argb(74, 255, 255, 255))
                setOnClickListener { finish() }
            }
            addView(back, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START or Gravity.TOP).apply { leftMargin = dp(28); topMargin = dp(42) })

            val title = TextView(context).apply {
                text = getString(R.string.cl_social_forum_title)
                setTextColor(Color.WHITE)
                textSize = 25f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            addView(title, FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP).apply { topMargin = dp(44) })

            val block = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), 0, dp(24), dp(26))
                gravity = Gravity.BOTTOM or Gravity.START
            }
            val weather = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(8), dp(13), dp(8))
                background = rounded(Color.argb(116, 0, 0, 0), dp(22), Color.argb(72, 255, 255, 255), 1)
            }
            weatherIcon = TextView(context).apply { text = "☀"; textSize = 19f; setTextColor(Color.YELLOW); typeface = Typeface.DEFAULT_BOLD }
            weatherText = TextView(context).apply {
                text = getString(R.string.cl_social_weather_location_pending)
                textSize = 13.5f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(8), 0, 0, 0)
                maxLines = 1
            }
            weather.addView(weatherIcon)
            weather.addView(weatherText, LinearLayout.LayoutParams(0, -2, 1f))
            block.addView(weather, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

            block.addView(TextView(context).apply {
                text = getString(R.string.cl_social_daily_brief)
                setTextColor(Color.WHITE)
                textSize = 40f
                typeface = Typeface.DEFAULT_BOLD
                includeFontPadding = false
            })
            block.addView(TextView(context).apply {
                text = "💬  ${getString(R.string.cl_social_forum_title)}     🖼  ${getString(R.string.cl_social_media)}"
                setTextColor(Color.WHITE)
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(10), 0, 0)
            })
            addView(block, FrameLayout.LayoutParams(-1, -1, Gravity.BOTTOM))
        }
    }

    private fun filterRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = rounded(Color.WHITE, dp(18), Color.rgb(227, 231, 238), 1)
        }
        listOf(Filter.ALL, Filter.MEDIA, Filter.LAYOVER).forEach { filter ->
            row.addView(filterButton(filter), LinearLayout.LayoutParams(0, dp(48), 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        return row
    }

    private fun filterButton(filter: Filter): TextView = TextView(this).apply {
        text = when (filter) {
            Filter.ALL -> getString(R.string.cl_social_filter_all)
            Filter.MEDIA -> getString(R.string.cl_social_filter_media)
            Filter.LAYOVER -> getString(R.string.cl_social_filter_layover)
        }
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setOnClickListener { currentFilter = filter; refreshFeed() }
        tag = filter
    }

    private fun updateFilterStyles() {
        val row = root.getChildAt(1) as? LinearLayout ?: return
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as? TextView ?: continue
            val selected = tv.tag == currentFilter
            tv.setTextColor(if (selected) Color.WHITE else Color.rgb(3, 27, 61))
            tv.background = if (selected) rounded(Color.rgb(255, 106, 0), dp(13)) else rounded(Color.rgb(242, 242, 247), dp(13))
        }
        root.getChildAt(3).visibility = if (currentFilter == Filter.MEDIA) View.GONE else View.VISIBLE
    }

    private fun searchRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(8), dp(14), dp(8))
        background = rounded(Color.WHITE, dp(18), Color.rgb(227, 231, 238), 1)
        addView(TextView(context).apply { text = "⌕"; textSize = 31f; setTextColor(Color.rgb(255, 106, 0)) })
        searchInput = EditText(context).apply {
            hint = getString(R.string.cl_social_search_placeholder)
            textSize = 18f
            setSingleLine(true)
            background = null
            setPadding(dp(8), 0, 0, 0)
            addTextChangedListener(SimpleTextWatcher { refreshFeed() })
        }
        addView(searchInput, LinearLayout.LayoutParams(0, dp(48), 1f))
    }

    private fun addMediaRow(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = rounded(Color.WHITE, dp(18), Color.rgb(227, 231, 238), 1)
        setOnClickListener { showComposer() }
        addView(TextView(context).apply {
            text = "+"
            textSize = 30f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = oval(Color.rgb(255, 106, 0))
        }, LinearLayout.LayoutParams(dp(50), dp(50)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, 0, 0)
            addView(TextView(context).apply { text = getString(R.string.cl_social_add_media); setTextColor(Color.rgb(3, 27, 61)); textSize = 17f; typeface = Typeface.DEFAULT_BOLD })
            addView(TextView(context).apply { text = getString(R.string.cl_social_add_media_hint); setTextColor(Color.rgb(142, 142, 147)); textSize = 16f })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(TextView(context).apply { text = "🖼  🎥"; textSize = 25f; setTextColor(Color.rgb(255, 106, 0)) })
    }

    private fun bottomBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(6), dp(8), dp(6))
        setBackgroundColor(Color.argb(245, 255, 255, 255))
        addFooterItem(this, "▦", getString(R.string.cl_social_tab_brief), false, null)
        addFooterItem(this, "✈", getString(R.string.cl_social_tab_flights), false, null)
        addFooterItem(this, "👥", getString(R.string.cl_social_tab_crew), true) { startActivity(Intent(this@CrewForumActivity, CrewNearbyActivity::class.java)) }
        addFooterItem(this, "💬", getString(R.string.cl_social_tab_messages), false) { startActivity(Intent(this@CrewForumActivity, CrewChatsActivity::class.java)) }
        addFooterItem(this, "…", getString(R.string.cl_social_tab_more), false, null)
    }

    private fun addFooterItem(parent: LinearLayout, icon: String, label: String, selected: Boolean, click: (() -> Unit)?) {
        parent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { click?.invoke() }
            addView(TextView(context).apply { text = icon; textSize = 24f; gravity = Gravity.CENTER; setTextColor(if (selected) Color.rgb(255, 106, 0) else Color.rgb(142, 142, 147)) })
            addView(TextView(context).apply { text = label; textSize = 11f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (selected) Color.rgb(255, 106, 0) else Color.rgb(142, 142, 147)) })
        }, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun bindStore() {
        store.posts.observe(this, Observer { posts -> allPosts = posts; refreshFeed() })
        store.isLoading.observe(this, Observer { progress.visibility = if (it == true) View.VISIBLE else View.GONE })
        store.error.observe(this, Observer { if (!it.isNullOrBlank()) Toast.makeText(this, it, Toast.LENGTH_LONG).show() })
        layoverStore.onlineNow.observe(this, Observer { rebuildActiveCrewIds() })
        layoverStore.activeLast24h.observe(this, Observer { rebuildActiveCrewIds() })
    }

    private fun rebuildActiveCrewIds() {
        val ids = mutableSetOf<String>()
        layoverStore.onlineNow.value.orEmpty().forEach { ids.add(it.userId) }
        layoverStore.activeLast24h.value.orEmpty().forEach { ids.add(it.userId) }
        com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid?.let { ids.add(it) }
        activeCrewIds = ids
        refreshFeed()
    }

    private fun refreshFeed() {
        updateFilterStyles()
        feedContainer.removeAllViews()
        val query = searchInput.text?.toString()?.trim().orEmpty()
        val tokens = query.split(Regex("\\s+")).filter { it.isNotBlank() }
        val filtered = allPosts.filter { post ->
            val filterOk = when (currentFilter) {
                Filter.ALL -> true
                Filter.MEDIA -> post.hasMedia()
                Filter.LAYOVER -> activeCrewIds.contains(post.uid)
            }
            filterOk && tokens.all { matchesSearchToken(it, post) }
        }
        if (currentFilter == Filter.MEDIA) {
            val media = filtered.flatMap { p -> listOfNotNull(p.photoStorageUrl?.let { p to it }, p.videoStorageUrl?.let { p to it }) }
            if (media.isEmpty()) addEmpty(getString(R.string.cl_social_empty_media))
            media.forEach { (post, url) -> feedContainer.addView(mediaCard(post, url), cardLp()) }
        } else {
            if (filtered.isEmpty()) addEmpty(getString(R.string.cl_social_empty_feed))
            filtered.forEach { feedContainer.addView(postCard(it), cardLp()) }
        }
    }

    private fun addEmpty(text: String) {
        feedContainer.addView(TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(142, 142, 147))
            textSize = 15f
            setPadding(0, dp(34), 0, dp(34))
            background = rounded(Color.WHITE, dp(18), Color.rgb(227, 231, 238), 1)
        }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
    }

    private fun postCard(post: CrewSocialPost): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = rounded(Color.WHITE, dp(22), Color.rgb(227, 231, 238), 1)
        addView(postHeader(post))
        if (post.text.isNotBlank()) addView(TextView(context).apply { text = post.text; textSize = 16f; setTextColor(Color.rgb(3, 27, 61)); setPadding(0, dp(10), 0, dp(8)) })
        post.photoStorageUrl?.let { addView(mediaPreview(it, false, post), LinearLayout.LayoutParams(-1, dp(210)).apply { bottomMargin = dp(8) }) }
        post.videoStorageUrl?.let { addView(mediaPreview(it, true, post), LinearLayout.LayoutParams(-1, dp(210)).apply { bottomMargin = dp(8) }) }
        post.layoverLocation?.takeIf { it.isNotBlank() }?.let {
            addView(chip("⌖  $it", Color.rgb(255, 243, 232), Color.rgb(3, 27, 61)), LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        }
        addView(reactionsRow(post))
    }

    private fun postHeader(post: CrewSocialPost): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(avatar(post.uid, post.profilePhotoUrl, dp(46)), LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply { text = post.nickname; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(3, 27, 61)); maxLines = 1 })
                addView(chip(getString(post.role.labelResId), Color.rgb(255, 239, 226), Color.rgb(255, 106, 0)), LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
            })
            addView(TextView(context).apply { text = listOfNotNull(post.airline, relative(post.createdAt)).joinToString("  "); textSize = 13f; setTextColor(Color.rgb(142, 142, 147)) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        if (isMine(post.uid)) addView(optionsButton { showPostOptions(post) }, LinearLayout.LayoutParams(dp(42), dp(42)))
    }

    private fun mediaPreview(url: String, isVideo: Boolean, post: CrewSocialPost?): View = FrameLayout(this).apply {
        background = rounded(Color.BLACK, dp(12))
        clipToOutline = true
        if (isVideo) {
            addView(VideoView(context).apply { setVideoURI(url.toUri()); setOnPreparedListener { it.isLooping = true; start() } }, FrameLayout.LayoutParams(-1, -1))
            addView(label("▶  ${getString(R.string.cl_social_video)}"), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { leftMargin = dp(8); topMargin = dp(8) })
        } else {
            val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(Color.rgb(242, 242, 247)) }
            addView(img, FrameLayout.LayoutParams(-1, -1)); loadImage(url, img)
        }
        addView(cornerButton("↗") { showFullscreen(url, isVideo) }, FrameLayout.LayoutParams(dp(38), dp(38), Gravity.END or Gravity.TOP).apply { rightMargin = dp(8); topMargin = dp(8) })
    }

    private fun reactionsRow(post: CrewSocialPost): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(reactionButton("♡", "♥", post.likesCount, post.isLikedByMe, Color.RED) { store.togglePostReaction(post.id, CrewSocialReactionKind.HEART) })
        addView(reactionButton("👍", "👍", post.thumbUpCount, post.isThumbedUpByMe, Color.rgb(52, 199, 89)) { store.togglePostReaction(post.id, CrewSocialReactionKind.THUMB_UP) })
        addView(reactionButton("👎", "👎", post.thumbDownCount, post.isThumbedDownByMe, Color.rgb(255, 106, 0)) { store.togglePostReaction(post.id, CrewSocialReactionKind.THUMB_DOWN) })
        addView(reactionButton("💬", "💬", post.commentsCount, false, Color.rgb(3, 27, 61)) { showComments(post) })
    }

    private fun mediaCard(post: CrewSocialPost, url: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        background = rounded(Color.WHITE, dp(22), Color.rgb(227, 231, 238), 1)
        val isVideo = url == post.videoStorageUrl
        addView(FrameLayout(context).apply {
            background = rounded(Color.BLACK, dp(18))
            if (isVideo) addView(VideoView(context).apply { setVideoURI(url.toUri()); setOnPreparedListener { it.isLooping = true; start() } }, FrameLayout.LayoutParams(-1, -1))
            else addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; loadImage(url, this) }, FrameLayout.LayoutParams(-1, -1))
            addView(label(if (isVideo) "▶  ${getString(R.string.cl_social_video)}" else "🖼  ${getString(R.string.cl_social_photo)}"), FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { leftMargin = dp(10); topMargin = dp(10) })
            val actions = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(cornerButton("⇩") { saveMedia(url, isVideo) }, LinearLayout.LayoutParams(dp(38), dp(38)))
            actions.addView(cornerButton("↗") { showFullscreen(url, isVideo) }, LinearLayout.LayoutParams(dp(38), dp(38)).apply { leftMargin = dp(8) })
            addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { rightMargin = dp(10); topMargin = dp(10) })
        }, LinearLayout.LayoutParams(-1, dp(410)).apply { bottomMargin = dp(8) })
        addView(postHeader(post))
    }

    private fun showComposer() {
        composerImageUri = null; composerVideoUri = null; composerLocation = null
        val dialog = Dialog(this)
        composerDialog = dialog
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(18), dp(22), dp(16))
            background = rounded(Color.WHITE, dp(28))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = getString(R.string.cancel_button); textSize = 18f; setOnClickListener { dialog.dismiss() } })
        top.addView(TextView(this).apply { text = getString(R.string.cl_social_daily_brief); gravity = Gravity.CENTER; textSize = 18f; typeface = Typeface.DEFAULT_BOLD }, LinearLayout.LayoutParams(0, -2, 1f))
        val publish = TextView(this).apply { text = getString(R.string.cl_social_publish); textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(255, 106, 0)) }
        top.addView(publish)
        box.addView(top)
        val input = EditText(this).apply {
            hint = getString(R.string.cl_social_new_post_placeholder)
            textSize = 24f
            minLines = 4
            maxLines = 6
            filters = arrayOf(InputFilter.LengthFilter(280))
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(Color.rgb(242, 242, 247), dp(16))
        }
        box.addView(input, LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(20) })
        composerPreview = TextView(this).apply { setTextColor(Color.rgb(142, 142, 147)); textSize = 14f; setPadding(0, dp(8), 0, 0) }
        box.addView(composerPreview)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(14), 0, 0) }
        actions.addView(MaterialButton(this).apply { text = getString(R.string.cl_social_add_media); setOnClickListener { pickPhoto.launch("image/*") } }, LinearLayout.LayoutParams(0, dp(52), 1f))
        actions.addView(MaterialButton(this).apply { text = "🎥"; setOnClickListener { pickVideo.launch("video/*") } }, LinearLayout.LayoutParams(dp(64), dp(52)).apply { leftMargin = dp(8) })
        box.addView(actions)
        composerLocationView = TextView(this).apply { text = "⌖  ${getString(R.string.cl_social_add_location)}"; textSize = 18f; setTextColor(Color.rgb(142, 142, 147)); setPadding(0, dp(14), 0, 0); setOnClickListener { resolveComposerLocation() } }
        box.addView(composerLocationView)
        publish.setOnClickListener {
            Toast.makeText(this, getString(R.string.cl_social_media_uploading), Toast.LENGTH_SHORT).show()
            store.submitPost(this, input.text.toString(), composerImageUri, composerVideoUri, composerLocation) { ok -> if (ok) dialog.dismiss() }
        }
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.window?.setLayout(-1, -2)
        dialog.show()
    }

    private fun resolveComposerLocation() {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!permission) { requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        CrewLocationManager.shared.start(this) { loc ->
            io.execute {
                val city = reverseCity(loc)
                runOnUiThread {
                    composerLocation = city
                    composerLocationView?.text = "⌖  ${city ?: getString(R.string.cl_social_add_location)}"
                }
            }
        }
    }

    private fun showComments(post: CrewSocialPost) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(Color.WHITE, dp(24)) }
        box.addView(TextView(this).apply { text = getString(R.string.cl_social_comments_title); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(list) }
        box.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val input = EditText(this).apply { hint = getString(R.string.cl_social_comment_placeholder); filters = arrayOf(InputFilter.LengthFilter(280)); background = rounded(Color.rgb(242, 242, 247), dp(12)); setPadding(dp(10), 0, dp(10), 0) }
        inputRow.addView(input, LinearLayout.LayoutParams(0, dp(48), 1f))
        inputRow.addView(TextView(this).apply { text = "➤"; gravity = Gravity.CENTER; textSize = 22f; setTextColor(Color.WHITE); background = rounded(Color.rgb(255, 106, 0), dp(11)); setOnClickListener { store.submitComment(post.id, input.text.toString()) { if (it != null) { input.setText(""); loadCommentsInto(post, list) } } } }, LinearLayout.LayoutParams(dp(46), dp(46)).apply { leftMargin = dp(8) })
        box.addView(inputRow)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(box)
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.82f).toInt())
        dialog.show()
        loadCommentsInto(post, list)
    }

    private fun loadCommentsInto(post: CrewSocialPost, list: LinearLayout) {
        store.loadComments(post.id) { comments ->
            runOnUiThread {
                list.removeAllViews()
                if (comments.isEmpty()) list.addView(TextView(this).apply { text = getString(R.string.cl_social_no_comments); gravity = Gravity.CENTER; setTextColor(Color.GRAY); setPadding(0, dp(28), 0, dp(28)) })
                comments.forEach { c -> list.addView(commentRow(post.id, c), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }) }
            }
        }
    }

    private fun commentRow(postId: String, comment: CrewSocialComment): View = LinearLayout(this).apply row@ {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, dp(8), 0, dp(8))
        val av = avatar(comment.uid, comment.profilePhotoUrl, dp(34))
        av.setOnClickListener { comment.profilePhotoUrl?.let { showFullscreen(it, false) } }
        addView(av, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, 0, 0)
            val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(TextView(context).apply { text = comment.nickname; typeface = Typeface.DEFAULT_BOLD; textSize = 14f; setTextColor(Color.rgb(3, 27, 61)) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (isMine(comment.uid)) head.addView(optionsButton { showCommentOptions(postId, comment, this@row) }, LinearLayout.LayoutParams(dp(36), dp(36)))
            addView(head)
            addView(TextView(context).apply { text = comment.text; textSize = 15f; setTextColor(Color.rgb(3, 27, 61)); setPadding(0, dp(2), 0, dp(7)) })
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(commentReactionButton("♡", comment.likesCount, comment.isLikedByMe) { store.toggleCommentReaction(postId, comment.id, CrewSocialReactionKind.HEART) { } })
                addView(commentReactionButton("👍", comment.thumbUpCount, comment.isThumbedUpByMe) { store.toggleCommentReaction(postId, comment.id, CrewSocialReactionKind.THUMB_UP) { } })
                addView(commentReactionButton("👎", comment.thumbDownCount, comment.isThumbedDownByMe) { store.toggleCommentReaction(postId, comment.id, CrewSocialReactionKind.THUMB_DOWN) { } })
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun showPostOptions(post: CrewSocialPost) {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.cl_social_edit_post), getString(R.string.cl_social_delete_post))) { _, which ->
                if (which == 0) showEditPost(post) else store.deletePost(post.id)
            }.show()
    }

    private fun showCommentOptions(postId: String, comment: CrewSocialComment, row: LinearLayout) {
        AlertDialog.Builder(this)
            .setItems(arrayOf(getString(R.string.cl_social_edit_comment), getString(R.string.cl_social_delete_comment))) { _, which ->
                if (which == 0) showEditComment(postId, comment) else store.deleteComment(postId, comment.id) { row.visibility = View.GONE }
            }.show()
    }

    private fun showEditPost(post: CrewSocialPost) {
        val text = EditText(this).apply { setText(post.text); filters = arrayOf(InputFilter.LengthFilter(280)); minLines = 4 }
        AlertDialog.Builder(this).setTitle(R.string.cl_social_edit_post).setView(text)
            .setNegativeButton(R.string.cancel_button, null)
            .setPositiveButton(R.string.save_button) { _, _ -> store.updatePost(post.id, text.text.toString(), post.layoverLocation) {} }
            .show()
    }

    private fun showEditComment(postId: String, comment: CrewSocialComment) {
        val text = EditText(this).apply { setText(comment.text); filters = arrayOf(InputFilter.LengthFilter(280)); minLines = 3 }
        AlertDialog.Builder(this).setTitle(R.string.cl_social_edit_comment).setView(text)
            .setNegativeButton(R.string.cancel_button, null)
            .setPositiveButton(R.string.save_button) { _, _ -> store.updateComment(postId, comment.id, text.text.toString()) {} }
            .show()
    }

    private fun showFullscreen(url: String, isVideo: Boolean) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        if (isVideo) frame.addView(VideoView(this).apply { setVideoURI(url.toUri()); setOnPreparedListener { start() } }, FrameLayout.LayoutParams(-1, -1))
        else frame.addView(ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; loadImage(url, this) }, FrameLayout.LayoutParams(-1, -1))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(cornerButton("⇩") { saveMedia(url, isVideo) }, LinearLayout.LayoutParams(dp(42), dp(42)))
        actions.addView(cornerButton("↙") { dialog.dismiss() }, LinearLayout.LayoutParams(dp(42), dp(42)).apply { leftMargin = dp(8) })
        frame.addView(actions, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply { topMargin = dp(24); rightMargin = dp(18) })
        dialog.setContentView(frame)
        dialog.show()
    }

    private fun saveMedia(url: String, isVideo: Boolean) {
        Toast.makeText(this, getString(R.string.cl_social_media_saving), Toast.LENGTH_SHORT).show()
        io.execute {
            try {
                val bytes = URL(url).openStream().use { it.readBytes() }
                val resolver = contentResolver
                val name = "CrewForum_${System.currentTimeMillis()}" + if (isVideo) ".mp4" else ".jpg"
                val collection = if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (isVideo) "video/mp4" else "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) put(MediaStore.MediaColumns.RELATIVE_PATH, if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES)
                }
                val uri = resolver.insert(collection, values) ?: throw IllegalStateException("No media uri")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                runOnUiThread { Toast.makeText(this, getString(R.string.cl_social_media_saved), Toast.LENGTH_SHORT).show() }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, getString(R.string.cl_social_media_save_failed), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun startLocationAndWeather() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) { requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION); return }
        CrewLocationManager.shared.start(this) { loc -> updateWeather(loc) }
    }

    private fun updateWeather(loc: Location) {
        io.execute {
            val city = reverseCity(loc)
            val status = fetchWeather(loc, city)
            runOnUiThread {
                weatherIcon.text = iconFor(status.weatherCode)
                weatherIcon.setTextColor(if ((status.weatherCode ?: 0) in 0..2) Color.YELLOW else Color.WHITE)
                weatherText.text = listOfNotNull(status.city, status.timeText, status.temperatureText, status.conditionText, status.windText?.let { "≋ $it" }).joinToString(" • ")
            }
        }
    }

    private fun fetchWeather(loc: Location, city: String?): CrewSocialWeatherStatus {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return try {
            val url = URL("https://api.open-meteo.com/v1/forecast?latitude=${loc.latitude}&longitude=${loc.longitude}&current=temperature_2m,weather_code,wind_speed_10m&timezone=auto&temperature_unit=celsius")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000; conn.readTimeout = 5000
            val body = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
            val temp = Regex("\"temperature_2m\":(-?\\d+(?:\\.\\d+)?)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()
            val code = Regex("\"weather_code\":(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
            val wind = Regex("\"wind_speed_10m\":(-?\\d+(?:\\.\\d+)?)").find(body)?.groupValues?.get(1)?.toDoubleOrNull()?.roundToInt()
            CrewSocialWeatherStatus(city, time, temp?.let { "$it°C" }, conditionFor(code), code, wind?.let { "$it km/h" })
        } catch (_: Exception) {
            CrewSocialWeatherStatus(city, time, null, null, null, null)
        }
    }

    private fun reverseCity(loc: Location): String? = try {
        Geocoder(this, Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()?.let { it.locality ?: it.subAdminArea ?: it.adminArea ?: it.countryName }
    } catch (_: Exception) { null }

    private fun conditionFor(code: Int?): String? = when (code) {
        0 -> getString(R.string.cl_social_weather_clear)
        1 -> getString(R.string.cl_social_weather_mainly_clear)
        2 -> getString(R.string.cl_social_weather_partly_cloudy)
        3 -> getString(R.string.cl_social_weather_overcast)
        45, 48 -> getString(R.string.cl_social_weather_fog)
        51, 53, 55, 56, 57 -> getString(R.string.cl_social_weather_drizzle)
        61, 63, 65, 66, 67 -> getString(R.string.cl_social_weather_rain)
        71, 73, 75, 77 -> getString(R.string.cl_social_weather_snow)
        80, 81, 82, 85, 86 -> getString(R.string.cl_social_weather_showers)
        95, 96, 99 -> getString(R.string.cl_social_weather_thunderstorm)
        else -> null
    }

    private fun iconFor(code: Int?): String = when (code) { 0, 1 -> "☀"; 2 -> "⛅"; 3, 45, 48 -> "☁"; 95, 96, 99 -> "⛈"; else -> "☁" }

    private fun matchesSearchToken(raw: String, post: CrewSocialPost): Boolean {
        val token = raw.lowercase(Locale.ROOT).trim()
        val bare = token.trimStart('@', '#').trim('.', ',', ';', ':', '!', '?')
        val hashtags = post.text.split(Regex("\\s+")).mapNotNull { it.lowercase(Locale.ROOT).trim('.', ',', ';', ':', '!', '?').takeIf { t -> t.startsWith("#") }?.drop(1) }
        return when {
            token.startsWith("@") -> post.nickname.lowercase(Locale.ROOT).contains(bare) || post.uid.lowercase(Locale.ROOT).contains(bare)
            token.startsWith("#") -> hashtags.any { it.contains(bare) }
            else -> post.text.lowercase(Locale.ROOT).contains(token) || post.nickname.lowercase(Locale.ROOT).contains(token) || post.uid.lowercase(Locale.ROOT).contains(token) || post.airline?.lowercase(Locale.ROOT)?.contains(token) == true || post.layoverLocation?.lowercase(Locale.ROOT)?.contains(token) == true || hashtags.any { it.contains(bare) }
        }
    }

    private fun avatar(uid: String, url: String?, size: Int): ImageView = ImageView(this).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        background = rounded(Color.rgb(242, 242, 247), (size * 0.22f).toInt(), Color.rgb(229, 229, 234), 1)
        clipToOutline = true
        val bitmap = CrewPhotoLoader.shared.image(uid)
        if (bitmap != null) setImageBitmap(bitmap) else if (!url.isNullOrBlank()) loadImage(url, this) else setImageResource(R.drawable.ic_people)
    }

    private fun loadImage(url: String, imageView: ImageView) {
        io.execute {
            try {
                val bmp = BitmapFactory.decodeStream(URL(url).openStream())
                runOnUiThread { imageView.setImageBitmap(bmp) }
            } catch (_: Exception) {}
        }
    }

    private fun reactionButton(icon: String, selectedIcon: String, count: Int, selected: Boolean, selectedColor: Int, click: () -> Unit): TextView = TextView(this).apply {
        text = (if (selected) selectedIcon else icon) + if (count > 0) " $count" else ""
        textSize = 20f
        gravity = Gravity.CENTER
        setTextColor(if (selected) selectedColor else Color.rgb(3, 27, 61))
        background = rounded(Color.rgb(242, 242, 247), dp(11))
        setOnClickListener { click() }
        layoutParams = LinearLayout.LayoutParams(dp(66), dp(44)).apply { rightMargin = dp(12) }
    }

    private fun commentReactionButton(icon: String, count: Int, selected: Boolean, click: () -> Unit): TextView = reactionButton(icon, icon, count, selected, Color.rgb(255, 106, 0), click).apply { textSize = 15f; layoutParams = LinearLayout.LayoutParams(dp(54), dp(34)).apply { rightMargin = dp(8) } }

    private fun optionsButton(click: () -> Unit): TextView = TextView(this).apply { text = "…"; textSize = 24f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.rgb(142, 142, 147)); background = oval(Color.rgb(242, 242, 247)); setOnClickListener { click() } }
    private fun cornerButton(text: String, click: () -> Unit): TextView = TextView(this).apply { this.text = text; textSize = 20f; gravity = Gravity.CENTER; setTextColor(Color.WHITE); background = oval(Color.argb(142, 0, 0, 0)); setOnClickListener { click() } }
    private fun label(text: String): TextView = TextView(this).apply { this.text = text; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(dp(8), dp(5), dp(8), dp(5)); background = rounded(Color.argb(130, 0, 0, 0), dp(14)) }
    private fun chip(text: String, bg: Int, fg: Int): TextView = TextView(this).apply { this.text = text; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(fg); setPadding(dp(9), dp(5), dp(9), dp(5)); background = rounded(bg, dp(14)) }
    private fun relative(date: Date): String = android.text.format.DateUtils.getRelativeTimeSpanString(date.time, System.currentTimeMillis(), android.text.format.DateUtils.SECOND_IN_MILLIS).toString()
    private fun isMine(uid: String): Boolean = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid == uid
    private fun cardLp() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) }
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
    private fun rounded(color: Int, radius: Int, strokeColor: Int? = null, strokeWidth: Int = 0) = GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat(); if (strokeColor != null) setStroke(dp(strokeWidth), strokeColor) }
    private fun oval(color: Int) = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }

    private enum class Filter { ALL, MEDIA, LAYOVER }
}
