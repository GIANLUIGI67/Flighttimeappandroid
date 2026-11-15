package it.grg.flighttimeapp.aletheia

import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Single-threaded llama.cpp runtime for Aletheia Offline GGUF.
 * Mirrors the iOS llmfarm / llama.cpp session pattern: load once, generate, clear KV.
 */
class AletheiaLlamaRuntime private constructor() {
    private val ready = AtomicBoolean(false)
    private val libraryOk = AtomicBoolean(false)

    private val runLoop = Executors.newSingleThreadExecutor { r ->
        thread(start = false, name = "Aletheia-Llama") {
            try {
                System.loadLibrary("aletheia_llama")
                AletheiaLlamaNative.nativeBackendInit()
                libraryOk.set(true)
                Log.i(TAG, AletheiaLlamaNative.nativeSystemInfo())
            } catch (t: Throwable) {
                libraryOk.set(false)
                Log.e(TAG, "Failed to load aletheia_llama native library", t)
            }
            r.run()
        }.apply {
            uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e ->
                Log.e(TAG, "Unhandled on llama thread", e)
            }
        }
    }.asCoroutineDispatcher()

    @Volatile private var model: Long = 0
    @Volatile private var context: Long = 0
    @Volatile private var batch: Long = 0
    @Volatile private var sampler: Long = 0
    @Volatile private var loadedPath: String? = null

    fun isNativeAvailable(): Boolean = libraryOk.get() || tryLoadProbe()

    private fun tryLoadProbe(): Boolean {
        // libraryOk is set on first runLoop task; callers should prefer ensureLoaded.
        return libraryOk.get()
    }

    suspend fun ensureLoaded(path: String, nCtx: Int = DEFAULT_N_CTX): Boolean =
        withContext(runLoop) {
            if (!libraryOk.get()) return@withContext false
            if (model != 0L && loadedPath == path) return@withContext true
            unloadLocked()
            try {
                val m = AletheiaLlamaNative.nativeLoadModel(path)
                if (m == 0L) return@withContext false
                val c = AletheiaLlamaNative.nativeNewContext(m, nCtx)
                if (c == 0L) {
                    AletheiaLlamaNative.nativeFreeModel(m)
                    return@withContext false
                }
                val b = AletheiaLlamaNative.nativeNewBatch(512, 0, 1)
                val s = AletheiaLlamaNative.nativeNewSampler()
                if (b == 0L || s == 0L) {
                    AletheiaLlamaNative.nativeFreeContext(c)
                    AletheiaLlamaNative.nativeFreeModel(m)
                    if (b != 0L) AletheiaLlamaNative.nativeFreeBatch(b)
                    if (s != 0L) AletheiaLlamaNative.nativeFreeSampler(s)
                    return@withContext false
                }
                model = m
                context = c
                batch = b
                sampler = s
                loadedPath = path
                ready.set(true)
                Log.i(TAG, "Loaded GGUF $path")
                true
            } catch (t: Throwable) {
                Log.e(TAG, "ensureLoaded failed", t)
                unloadLocked()
                false
            }
        }

    suspend fun generate(prompt: String, maxTokens: Int = DEFAULT_MAX_TOKENS): String =
        withContext(runLoop) {
            if (!ready.get() || context == 0L) {
                throw IllegalStateException("GGUF model not loaded")
            }
            val nLen = maxTokens.coerceIn(16, 512)
            val ncur = AletheiaLlamaNative.IntVar(
                AletheiaLlamaNative.nativeCompletionInit(
                    context, batch, prompt, /* formatChat = */ true, nLen
                )
            )
            val out = StringBuilder()
            while (ncur.getValue() <= nLen) {
                val piece = AletheiaLlamaNative.nativeCompletionLoop(
                    context, batch, sampler, nLen, ncur
                ) ?: break
                out.append(piece)
            }
            AletheiaLlamaNative.nativeKvClear(context)
            out.toString().trim()
        }

    suspend fun unload() = withContext(runLoop) { unloadLocked() }

    private fun unloadLocked() {
        if (context != 0L) AletheiaLlamaNative.nativeFreeContext(context)
        if (model != 0L) AletheiaLlamaNative.nativeFreeModel(model)
        if (batch != 0L) AletheiaLlamaNative.nativeFreeBatch(batch)
        if (sampler != 0L) AletheiaLlamaNative.nativeFreeSampler(sampler)
        context = 0
        model = 0
        batch = 0
        sampler = 0
        loadedPath = null
        ready.set(false)
    }

    companion object {
        private const val TAG = "AletheiaLlama"
        const val DEFAULT_N_CTX = 2048
        const val DEFAULT_MAX_TOKENS = 256

        @Volatile private var instance: AletheiaLlamaRuntime? = null

        fun get(): AletheiaLlamaRuntime {
            return instance ?: synchronized(this) {
                instance ?: AletheiaLlamaRuntime().also { instance = it }
            }
        }
    }
}
