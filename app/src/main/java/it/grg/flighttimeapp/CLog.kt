package it.grg.flighttimeapp

import android.util.Log

object CLog {
    fun d(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.d(tag, msg) }
    fun e(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.e(tag, msg) }
    fun w(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.w(tag, msg) }
    fun i(tag: String, msg: String) { if (BuildConfig.DEBUG) Log.i(tag, msg) }
}
