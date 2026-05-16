package it.grg.flighttimeapp.crewl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import androidx.core.content.edit

class CrewPhotoLoader private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val cache = LruCache<String, Bitmap>(64)
    private val maxExtraImages = 4
    /** Tracks which Storage URL is currently cached per user, to detect replacements. */
    private val cachedUrlByUserId = mutableMapOf<String, String>()
    /** Prevents duplicate in-flight fetches for the same user/url pair. */
    private val inFlightUrlByUserId = mutableMapOf<String, String>()

    // MARK: - Disk cache (survives LruCache eviction — same pattern as iOS)

    private fun diskCacheDir(): java.io.File {
        val dir = java.io.File(appContext.cacheDir, DISK_CACHE_FOLDER)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun diskCacheFile(userId: String): java.io.File =
        java.io.File(diskCacheDir(), "${userId}.jpg")

    private fun loadFromDisk(userId: String): Bitmap? {
        val file = diskCacheFile(userId)
        if (!file.exists()) return null
        return try { BitmapFactory.decodeFile(file.absolutePath) } catch (_: Exception) { null }
    }

    private fun saveToDisk(userId: String, bytes: ByteArray) {
        Thread {
            try {
                val file = diskCacheFile(userId)
                file.outputStream().use { it.write(bytes) }
            } catch (_: Exception) {}
        }.start()
    }

    private fun deleteDiskCacheFile(userId: String) {
        try { diskCacheFile(userId).delete() } catch (_: Exception) {}
    }

    private fun persistedUrl(userId: String): String? {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString("$KEY_PHOTO_URL_PREFIX$userId", null)
    }

    private fun setPersistedUrl(userId: String, url: String?) {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            if (url != null) putString("$KEY_PHOTO_URL_PREFIX$userId", url)
            else remove("$KEY_PHOTO_URL_PREFIX$userId")
        }
    }

    /**
     * Returns the cached image for a user.
     * Falls back to the disk cache if LruCache has evicted the image,
     * so the view never sees a placeholder for a photo that was already loaded.
     */
    fun image(userId: String): Bitmap? {
        cache.get(userId)?.let { return it }
        // LruCache miss — check disk before returning null (prevents placeholder flash)
        val diskBitmap = loadFromDisk(userId) ?: return null
        cache.put(userId, diskBitmap)
        return diskBitmap
    }

    fun getBitmap(userId: String, b64: String?): Bitmap? {
        val cached = cache.get(userId)
        if (cached != null) return cached
        if (b64.isNullOrBlank()) return null
        upsertFromBase64(userId, b64)
        return cache.get(userId)
    }

    fun upsertFromBase64(userId: String, b64: String, maxDimension: Int = 512) {
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
        val bmp = data?.let { decodeBitmapWithExif(it, maxDimension) }
        if (bmp != null) {
            cache.put(userId, bmp)
        }
    }

    fun prefetchFromBase64(userId: String, b64: String, maxDimension: Int = 512) {
        if (b64.isBlank()) return
        if (cache.get(userId) != null) return
        Thread {
            val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
            val bmp = data?.let { decodeBitmapWithExif(it, maxDimension) }
            if (bmp != null) {
                cache.put(userId, bmp)
            }
        }.start()
    }

    fun invalidate(userId: String) {
        cache.remove(userId)
        cachedUrlByUserId.remove(userId)
        deleteDiskCacheFile(userId)
        setPersistedUrl(userId, null)
    }

    fun setLocalProfileImage(bitmap: Bitmap) {
        val bytes = BitmapUtils.toJpeg(bitmap, 88)
        if (bytes != null) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit { putString(KEY_PROFILE_B64, Base64.encodeToString(bytes, Base64.NO_WRAP)) }
        }
    }

    fun myLocalProfileImage(): Bitmap? {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b64 = prefs.getString(KEY_PROFILE_B64, null) ?: return null
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
        return data?.let { decodeBitmapWithExif(it) }
    }

    fun localProfileExtraImages(): List<Bitmap> {
        val names = loadExtraNames()
        return names.mapNotNull { name ->
            val file = extraFile(name)
            if (!file.exists()) return@mapNotNull null
            BitmapFactory.decodeFile(file.absolutePath)
        }
    }

    fun appendLocalProfileExtraImage(bitmap: Bitmap) {
        val names = loadExtraNames()
        if (names.size >= maxExtraImages) return
        val bytes = BitmapUtils.toJpeg(bitmap, 82) ?: return
        val name = "extra_${System.currentTimeMillis()}_${names.size}.jpg"
        val file = extraFile(name)
        try {
            file.outputStream().use { it.write(bytes) }
            names.add(name)
            saveExtraNames(names)
        } catch (_: Exception) {
            return
        }
    }

    fun removeLocalProfileExtraImage(index: Int) {
        val names = loadExtraNames()
        if (index < 0 || index >= names.size) return
        val name = names.removeAt(index)
        val file = extraFile(name)
        if (file.exists()) {
            file.delete()
        }
        saveExtraNames(names)
    }

    // MARK: - URL-based loading (Firebase Storage)

    /**
     * Downloads a photo from a Storage URL on a background thread and stores it
     * in the in-memory cache keyed by userId. Returns immediately if already cached.
     * The [onResult] callback fires on the main thread.
     */
    fun loadFromUrl(url: String, userId: String, onResult: ((Bitmap?) -> Unit)? = null) {
        if (url.isBlank()) { onResult?.invoke(null); return }

        // Resolve last known URL (in-memory wins, then persisted across launches)
        val knownUrl = synchronized(this) { cachedUrlByUserId[userId] } ?: persistedUrl(userId)

        synchronized(this) {
            // URL changed → user replaced their photo, drop stale cache
            if (knownUrl != url) {
                cache.remove(userId)
                cachedUrlByUserId.remove(userId)
                deleteDiskCacheFile(userId)
            }
            if (inFlightUrlByUserId[userId] == url) {
                cache.get(userId)?.let { onResult?.invoke(it); return }
                return
            }
            inFlightUrlByUserId[userId] = url
        }

        // LruCache hit
        cache.get(userId)?.let {
            synchronized(this) { inFlightUrlByUserId.remove(userId) }
            onResult?.invoke(it)
            return
        }

        // Disk hit → warm LruCache, return immediately without network
        if (knownUrl == url) {
            loadFromDisk(userId)?.let { diskBitmap ->
                synchronized(this) {
                    cache.put(userId, diskBitmap)
                    cachedUrlByUserId[userId] = url
                    inFlightUrlByUserId.remove(userId)
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post { onResult?.invoke(diskBitmap) }
                return
            }
        }

        Thread {
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                connection.useCaches = false
                connection.doInput = true
                connection.connect()
                var bitmap: Bitmap? = null
                if (connection.responseCode == 200) {
                    val bytes = connection.inputStream.readBytes()
                    bitmap = decodeBitmapWithExif(bytes, 1024)
                    if (bitmap != null) {
                        // Persist to disk so future LruCache evictions don't show the placeholder
                        saveToDisk(userId, bytes)
                        setPersistedUrl(userId, url)
                    }
                }
                if (bitmap != null) {
                    synchronized(this) {
                        if (inFlightUrlByUserId[userId] == url) {
                            cache.put(userId, bitmap)
                            cachedUrlByUserId[userId] = url
                        }
                    }
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    synchronized(this) {
                        if (inFlightUrlByUserId[userId] == url) inFlightUrlByUserId.remove(userId)
                    }
                    onResult?.invoke(bitmap)
                }
            } catch (_: Exception) {
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    synchronized(this) {
                        if (inFlightUrlByUserId[userId] == url) inFlightUrlByUserId.remove(userId)
                    }
                    onResult?.invoke(null)
                }
            }
        }.start()
    }

    fun prefetchFromUrl(url: String, userId: String) {
        loadFromUrl(url, userId, null)
    }

    /**
     * Downloads the first URL in the list as the primary photo. Subsequent photos
     * are downloaded only if the first succeeds (lazy). All cached under userId.
     */
    fun loadListFromUrls(urls: List<String>, userId: String, onResult: ((Bitmap?) -> Unit)? = null) {
        if (urls.isEmpty()) { onResult?.invoke(null); return }
        loadFromUrl(urls.first(), userId, onResult)
    }

    fun decodeBase64ToBitmap(b64: String?): Bitmap? {
        if (b64.isNullOrBlank()) return null
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
        return data?.let { decodeBitmapWithExif(it) }
    }

    private fun decodeBitmapWithExif(bytes: ByteArray, maxDimension: Int = 0): Bitmap? {
        val sampleSize = if (maxDimension > 0) {
            val sizeOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, sizeOpts)
            var s = 1
            while ((maxOf(sizeOpts.outWidth, sizeOpts.outHeight) / s) > maxDimension) s = s shl 1
            s
        } else 1
        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts) ?: return null
        return try {
            val exif = ExifInterface(ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> rotate(bmp, 90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> rotate(bmp, 180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> rotate(bmp, 270f)
                else -> bmp
            }
        } catch (_: Exception) {
            bmp
        }
    }

    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    companion object {
        private const val PREFS = "crew_layover_prefs"
        private const val KEY_PROFILE_B64 = "crew_profile_image_b64"
        private const val KEY_PROFILE_EXTRA_NAMES = "crew_profile_extra_names"
        private const val EXTRA_FOLDER = "crew_profile_extra_images"
        private const val DISK_CACHE_FOLDER = "crew_photo_disk_cache"
        private const val KEY_PHOTO_URL_PREFIX = "crew_photo_url_v2_"
        @Volatile private var instance: CrewPhotoLoader? = null

        fun get(context: Context): CrewPhotoLoader {
            return instance ?: synchronized(this) {
                instance ?: CrewPhotoLoader(context).also { instance = it }
            }
        }

        @Volatile lateinit var shared: CrewPhotoLoader

        fun init(context: Context) {
            shared = get(context)
        }
    }

    private fun extraDir(): java.io.File {
        val dir = java.io.File(appContext.filesDir, EXTRA_FOLDER)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    private fun extraFile(name: String): java.io.File {
        return java.io.File(extraDir(), name)
    }

    private fun loadExtraNames(): MutableList<String> {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILE_EXTRA_NAMES, "") ?: ""
        if (raw.isBlank()) return mutableListOf()
        return raw.split("|").mapNotNull { it.trim().ifEmpty { null } }.toMutableList()
    }

    private fun saveExtraNames(names: List<String>) {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_PROFILE_EXTRA_NAMES, names.joinToString("|")) }
    }
}

object BitmapUtils {
    fun toJpeg(bitmap: Bitmap, quality: Int): ByteArray? {
        return try {
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
            stream.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    fun toBase64(bitmap: Bitmap, quality: Int = 88): String {
        val bytes = toJpeg(bitmap, quality) ?: return ""
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
