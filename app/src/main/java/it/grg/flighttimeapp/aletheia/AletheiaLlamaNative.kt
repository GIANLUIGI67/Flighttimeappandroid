package it.grg.flighttimeapp.aletheia

/**
 * JNI surface for [aletheia_llama] (llama.cpp). Loaded once on the dedicated run-loop thread.
 */
internal object AletheiaLlamaNative {
    @JvmStatic external fun nativeBackendInit()
    @JvmStatic external fun nativeBackendFree()
    @JvmStatic external fun nativeSystemInfo(): String
    @JvmStatic external fun nativeLoadModel(path: String): Long
    @JvmStatic external fun nativeFreeModel(model: Long)
    @JvmStatic external fun nativeNewContext(model: Long, nCtx: Int): Long
    @JvmStatic external fun nativeFreeContext(context: Long)
    @JvmStatic external fun nativeNewBatch(nTokens: Int, embd: Int, nSeqMax: Int): Long
    @JvmStatic external fun nativeFreeBatch(batch: Long)
    @JvmStatic external fun nativeNewSampler(): Long
    @JvmStatic external fun nativeFreeSampler(sampler: Long)
    @JvmStatic external fun nativeCompletionInit(
        context: Long,
        batch: Long,
        text: String,
        formatChat: Boolean,
        nLen: Int
    ): Int
    @JvmStatic external fun nativeCompletionLoop(
        context: Long,
        batch: Long,
        sampler: Long,
        nLen: Int,
        ncur: IntVar
    ): String?
    @JvmStatic external fun nativeKvClear(context: Long)

    /** Mutable int for JNI completion loop; exposes getValue()/inc() for native. */
    class IntVar(initial: Int) {
        @Volatile
        private var stored: Int = initial

        fun getValue(): Int = stored

        fun inc() {
            synchronized(this) { stored += 1 }
        }
    }
}
