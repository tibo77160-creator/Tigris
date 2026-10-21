package com.leslie.tvhome

import android.content.Context
import android.content.SharedPreferences

/** All user settings in one place. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("tvhome", Context.MODE_PRIVATE)

    /** One-time defaults already applied (dock order, background) for this build. */
    var setupVersion: Int
        get() = sp.getInt("setup_version", 0)
        set(v) = sp.edit().putInt("setup_version", v).apply()

    var order: List<String>
        get() = sp.getString("order", "")!!.split(',').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("order", v.joinToString(",")).apply()

    var hidden: Set<String>
        get() = sp.getStringSet("hidden", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("hidden", v).apply()

    /** Packages that memory cleanup never stops (casting receivers etc.). */
    var keep: Set<String>
        get() = sp.getStringSet("keep", DEFAULT_KEEP)!!.toSet()
        set(v) = sp.edit().putStringSet("keep", v).apply()

    var cleanOnStandby: Boolean
        get() = sp.getBoolean("clean_on_standby", true)
        set(v) = sp.edit().putBoolean("clean_on_standby", v).apply()

    /** Hours between automatic cleanups; 0 = off. */
    var cleanIntervalHours: Int
        get() = sp.getInt("clean_interval", 4)
        set(v) = sp.edit().putInt("clean_interval", v).apply()

    var shutdownOnPowerKey: Boolean
        get() = sp.getBoolean("shutdown_on_power", false)
        set(v) = sp.edit().putBoolean("shutdown_on_power", v).apply()

    /** One of [Backgrounds.THEMES] keys, or a rotating photo source: "daily" (Bing) or "anime" (Wallhaven). */
    var background: String
        get() = sp.getString("background", "rockies")!!
        set(v) = sp.edit().putString("background", v).apply()

    /** Minutes between background photo changes. */
    var wallMinutes: Int
        get() = sp.getInt("wall_minutes", 30)
        set(v) = sp.edit().putInt("wall_minutes", v).apply()

    /** Seconds each photo stays up in the photo frame. */
    var frameSeconds: Int
        get() = sp.getInt("frame_seconds", 30)
        set(v) = sp.edit().putInt("frame_seconds", v).apply()

    /** The photo frame is the system screensaver (instead of Sony's). */
    var frameScreensaver: Boolean
        get() = sp.getBoolean("frame_screensaver", false)
        set(v) = sp.edit().putBoolean("frame_screensaver", v).apply()

    /** Preinstalled apps removed for this user from the long-press menu; restorable in settings. */
    var removed: Set<String>
        get() = sp.getStringSet("removed", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("removed", v).apply()

    var adbAuthorized: Boolean
        get() = sp.getBoolean("adb_ok", false)
        set(v) = sp.edit().putBoolean("adb_ok", v).apply()

    var lastClean: String
        get() = sp.getString("last_clean", "")!!
        set(v) = sp.edit().putString("last_clean", v).apply()

    /** The screensaver before the photo frame took over, put back when it is turned off. */
    var prevScreensaver: String
        get() = sp.getString("prev_screensaver", "")!!
        set(v) = sp.edit().putString("prev_screensaver", v).apply()

    /** What the photo frame / screensaver shows: [SAVER_PHOTOS] or [SAVER_AERIAL]. */
    var saverMode: String
        get() = sp.getString("saver_mode", SAVER_PHOTOS)!!
        set(v) = sp.edit().putString("saver_mode", v).apply()

    /** Keep the home screen in front on firmwares that pin Home to their own launcher (Guard.kt). */
    var guard: Boolean
        get() = sp.getBoolean("guard", false)
        set(v) = sp.edit().putBoolean("guard", v).apply()

    /** Switch ADB debugging back on whenever the firmware turns it off (Xiaomi does on wake). */
    var keepAdb: Boolean
        get() = sp.getBoolean("keep_adb", false)
        set(v) = sp.edit().putBoolean("keep_adb", v).apply()

    /** Kept on disk while the home screen is in front, so a new process can tell how the last one ended. */
    var homeInFront: Boolean
        get() = sp.getBoolean("home_in_front", false)
        set(v) = sp.edit().putBoolean("home_in_front", v).apply()

    /** Picture links for the "custom" background, one per line (an image, or an API that returns one). */
    var customWallUrls: String
        get() = sp.getString("custom_urls", "")!!
        set(v) = sp.edit().putString("custom_urls", v).apply()

    /** TV inputs pinned to the home grid, as [InputTile.key] strings. */
    var pinnedInputs: Set<String>
        get() = sp.getStringSet("pinned_inputs", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("pinned_inputs", v).apply()

    companion object {
        const val SAVER_PHOTOS = "photos"
        const val SAVER_AERIAL = "aerial"

        val DEFAULT_KEEP = setOf(
            "com.dangbei.screencast",
            "com.xiaodianshi.tv.yst",
            "dev.vodik7.atvtoolsserver",
        )

        /** Initial grid order on first run; everything else follows alphabetically. */
        val DEFAULT_ORDER = listOf(
            // The dock (first row of 6).
            "com.netflix.ninja",
            "com.amazon.amazonvideo.livingroom",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.crunchyroll.crunchyroid",
            "com.apple.atve.androidtv.appletv",
            // Just below.
            "tv.twitch.android.app",
            "com.stremio.one",
            "fr.bouyguestelecom.tv.bbui",
            "fr.francetv.pluzz",
            "fr.m6.m6replay",
            "fr.tf1.mytf1",
            "com.android.tv.settings",
        )
    }
}
