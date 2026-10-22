package com.hardwareshop.pos.util

import com.hardwareshop.pos.BuildConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart

/** Counts/timing only; no cart contents, product names, prices or credentials. */
object BillingLoadDiagnostics {
    fun event(stage: String, count: Int = 0) {
        if (BuildConfig.DEBUG) android.util.Log.i("NavigationProbe",
            "$stage uptimeMs=${android.os.SystemClock.elapsedRealtime()} rows=$count thread=${Thread.currentThread().name}")
    }
    fun <T> observe(flow: Flow<T>, stage: String, count: (T) -> Int): Flow<T> =
        if (!BuildConfig.DEBUG) flow else flow
            .onStart { event("$stage-subscribe") }
            .onEach { event("$stage-emitted",count(it)) }
}
