package com.leslie.tvhome

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Talks to the TV's own adbd on 127.0.0.1:5555 so a normal app can run
 * shell-privileged commands (force-stop, shutdown). The first connection
 * makes the TV show "允许调试吗？"; after "始终允许" it is silent.
 */
object LocalAdb {
    private const val TAG = "LocalAdb"

    private fun keys(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /**
     * Runs [cmd] in the adb shell. Returns null when adbd is unreachable or
     * the key is not authorized yet. [waitAuthMs] is how long to wait for the
     * user to accept the authorization prompt.
     */
    fun shell(ctx: Context, cmd: String, waitAuthMs: Int = 4000): String? {
        val prefs = Prefs(ctx)
        return try {
            Dadb.create("127.0.0.1", 5555, keys(ctx), connectTimeout = 3000, socketTimeout = waitAuthMs).use { d ->
                d.shell(cmd).allOutput.also { prefs.adbAuthorized = true }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "adb shell failed: $cmd", e)
            null
        }
    }

    /** Fire-and-forget variant for commands that kill the connection (shutdown). */
    fun shellAsync(ctx: Context, cmd: String) {
        Thread { shell(ctx.applicationContext, cmd, 60_000) }.start()
    }
}

object Cleaner {
    data class Result(val beforeMb: Long, val afterMb: Long, val stopped: Int, val viaAdb: Boolean) {
        val freedMb get() = (afterMb - beforeMb).coerceAtLeast(0)
    }

    fun availMb(ctx: Context): Long = memInfo(ctx).availMem / 1024 / 1024
    fun totalMb(ctx: Context): Long = memInfo(ctx).totalMem / 1024 / 1024

    private fun memInfo(ctx: Context) = ActivityManager.MemoryInfo().also {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it)
    }

    /**
     * Stops third-party apps except the keep list and [spare] (the app in
     * front, when the screen is on), then kills cached background processes.
     * Blocking; call off the main thread.
     */
    fun clean(ctx: Context, spare: String? = null): Result {
        val prefs = Prefs(ctx)
        val before = availMb(ctx)
        val keep = prefs.keep + setOfNotNull(ctx.packageName, spare)
        var stopped = 0
        var viaAdb = false

        val list = LocalAdb.shell(ctx, "pm list packages -3")
        if (list != null) {
            val targets = list.lineSequence()
                .map { it.removePrefix("package:").trim() }
                .filter { it.isNotEmpty() && it !in keep }
                .toList()
            val script = targets.joinToString("; ") { "am force-stop $it" } + "; am kill-all; echo done"
            if (LocalAdb.shell(ctx, script, 15_000) != null) {
                stopped = targets.size
                viaAdb = true
            }
        }
        if (!viaAdb) {
            // No adb authorization yet: only cached background processes can go.
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            for (app in ctx.packageManager.getInstalledApplications(0)) {
                if (app.packageName in keep) continue
                am.killBackgroundProcesses(app.packageName)
            }
        }
        Thread.sleep(1500)
        val r = Result(before, availMb(ctx), stopped, viaAdb)
        prefs.lastClean = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE).format(Date()) +
            ", ${r.freedMb} Mo libérés"
        return r
    }

    fun foregroundPackage(ctx: Context): String? =
        LocalAdb.shell(ctx, "dumpsys activity activities | grep -m1 topResumedActivity")
            ?.let { Regex("""u0 ([\w.]+)/""").find(it)?.groupValues?.get(1) }

    fun shutdown(ctx: Context) = LocalAdb.shellAsync(ctx, "svc power shutdown")
    fun reboot(ctx: Context) = LocalAdb.shellAsync(ctx, "svc power reboot")
    fun sleep(ctx: Context) = LocalAdb.shellAsync(ctx, "input keyevent KEYCODE_SLEEP")
}
