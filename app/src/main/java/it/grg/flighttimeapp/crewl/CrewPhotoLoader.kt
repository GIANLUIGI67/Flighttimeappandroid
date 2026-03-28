package it.grg.flighttimeapp.crewl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.LruCache

class CrewPhotoLoader private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val cache = LruCache<String, Bitmap>(64)

    fun image(userId: String): Bitmap? = cache.get(userId)

    fun getBitmap(userId: String, b64: String?): Bitmap? {
        val cached = cache.get(userId)
        if (cached != null) return cached
        if (b64.isNullOrBlank()) return null
        upsertFromBase64(userId, b64)
        return cache.get(userId)
    }

    fun upsertFromBase64(userId: String, b64: String) {
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
        val bmp = data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        if (bmp != null) {
            cache.put(userId, bmp)
        }
    }

    fun invalidate(userId: String) {
        cache.remove(userId)
    }

    fun setLocalProfileImage(bitmap: Bitmap) {
        val bytes = BitmapUtils.toJpeg(bitmap, 88)
        if (bytes != null) {
            val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_PROFILE_B64, Base64.encodeToString(bytes, Base64.NO_WRAP)).apply()
        }
    }

    fun myLocalProfileImage(): Bitmap? {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val b64 = prefs.getString(KEY_PROFILE_B64, null) ?: return null
        val data = try { Base64.decode(b64, Base64.DEFAULT) } catch (_: Exception) { null }
        return data?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    companion object {
        private const val PREFS = "crew_layover_prefs"
        private const val KEY_PROFILE_B64 = "crew_profile_image_b64"
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
