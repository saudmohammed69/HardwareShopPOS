package com.hardwareshop.pos

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hardwareshop.pos.data.local.database.AppDatabase
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Synthetic debug emulator evidence only. Never included in the release APK. */
internal object NavigationFailureEvidence {
    fun startMainThreadProbe(): Closeable {
        val stopped=AtomicBoolean(false)
        val handler=Handler(Looper.getMainLooper())
        var expected=SystemClock.elapsedRealtime()+250
        val tick=object:Runnable { override fun run() {
            if(stopped.get())return
            val now=SystemClock.elapsedRealtime()
            if(now-expected>100)android.util.Log.i("NavigationProbe","main-thread-lagMs=${now-expected}")
            expected=now+250;handler.postDelayed(this,250)
        } }
        handler.postDelayed(tick,250)
        return Closeable { stopped.set(true);handler.removeCallbacks(tick) }
    }

    fun capture(activity: Activity, failure: Throwable, semantics: () -> String) {
        check(activity.packageName.endsWith(".debug") &&
            android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        val directory=File(activity.getExternalFilesDir(null),"phase4j-failure-${System.currentTimeMillis()}")
        check(directory.mkdir())
        File(directory,"failure.txt").writeText(failure.stackTraceToString())
        File(directory,"capture-time.txt").writeText("failureCaptureWallMs=${System.currentTimeMillis()} uptimeMs=${SystemClock.elapsedRealtime()}\n")
        File(directory,"threads.txt").writeText(Thread.getAllStackTraces().entries.joinToString("\n\n") {
            (thread,stack)->"${thread.name} ${thread.state}\n"+stack.joinToString("\n")
        })
        // VACUUM INTO produces a standalone SQLite snapshot including committed
        // WAL content. No live shop connection, checkpoint truncation or restore.
        runCatching {
            val db=AppDatabase.getInstance(activity.applicationContext)
            val snapshot=File(directory,"pre-teardown-room.sqlite")
            db.openHelper.writableDatabase.execSQL("VACUUM INTO '${snapshot.absolutePath.replace("'","''")}'")
            check(snapshot.length()>0)
            val digest=snapshot.inputStream().use { input ->
                val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(65536)
                while(true){val count=input.read(buffer);if(count<0)break;hash.update(buffer,0,count)}
                hash.digest().joinToString(""){"%02x".format(it)}
            }
            File(directory,"snapshot.sha256").writeText("$digest  ${snapshot.name}\n")
        }.onFailure { File(directory,"snapshot-error.txt").writeText(it.stackTraceToString()) }
        File(directory,"semantics.txt").writeText(runCatching(semantics).getOrElse { "Semantics unavailable: ${it.javaClass.simpleName}" })
        runCatching {
            val instrumentation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(directory,"screen.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                bitmap.recycle()
            }
        }
        android.util.Log.e("NavigationProbe","Failure evidence preserved BEFORE teardown: ${directory.name}")
    }
}
