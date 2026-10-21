package com.leslie.tvhome

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Brightness, volume and HDMI inputs for the Control Center. Sony's BRAVIA
 * API is used when the TV answers it; every other TV falls back to standard
 * Android APIs. To support another brand's own API, add it here.
 *
 * TCL / FFalcon China firmware (tcl_mt5879_cn and relatives) is handled by
 * [Tcl]: no TvInputFramework inputs are registered, the backlight only lives
 * in TCL's private PQ stack, and TCL's own settings app is the only thing
 * that answers a settings intent. Xiaomi's China firmware is handled by [Xiaomi].
 */
class TvControls(private val ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var braviaVolume = false
    private var xiaomiBacklight = false

    /** The backlight has no standard Android API, so this is null (and the slider hidden) off Sony and Xiaomi TVs. */
    fun brightness(): Bravia.Level? {
        Bravia.brightness()?.let { xiaomiBacklight = false; return it }
        return Xiaomi.brightness(ctx).also { xiaomiBacklight = it != null }
    }

    fun setBrightness(v: Int) = if (xiaomiBacklight) Xiaomi.setBrightness(ctx, v) else Bravia.setBrightness(v)

    fun volume(): Bravia.Level? {
        Bravia.volume()?.let { braviaVolume = true; return it }
        braviaVolume = false
        val s = AudioManager.STREAM_MUSIC
        return Bravia.Level(audio.getStreamVolume(s), audio.getStreamMinVolume(s), audio.getStreamMaxVolume(s))
    }

    fun setVolume(target: String, v: Int): Boolean {
        if (braviaVolume) return Bravia.setVolume(target, v)
        // TCL only pushes the new value to the hardware while the system volume bar is shown,
        // so FLAG_SHOW_UI is required there too; it is harmless elsewhere.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, v, AudioManager.FLAG_SHOW_UI)
        return true
    }

    fun inputs(): List<Bravia.Input> {
        val sony = Bravia.inputs()
        if (sony.isNotEmpty()) return sony
        val android = androidInputs()
        return if (android.isNotEmpty()) android else Tcl.inputs(ctx)
    }

    /** Uri of the HDMI input being shown; only Sony reports it. */
    fun currentInput(): String? = Bravia.currentInput()

    fun setInput(i: Bravia.Input) {
        if (i.uri.startsWith(Tcl.URI_PREFIX)) { Tcl.setInput(ctx); return }
        if (i.uri.startsWith(Xiaomi.URI_PREFIX)) { Xiaomi.setInput(ctx, i.uri); return }
        if (!i.uri.startsWith("content:")) { Bravia.setInput(i.uri); return }
        // Android's way to show an HDMI input: open its passthrough channel in the TV's own player.
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(i.uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.w("TvControls", "cannot open ${i.uri}", e)
        }
    }

    /** The TV maker's own settings app where the standard intent is not answered (TCL, Xiaomi). */
    fun settingsIntent(): Intent = Tcl.settingsIntent(ctx) ?: Xiaomi.settingsIntent(ctx) ?: Intent(android.provider.Settings.ACTION_SETTINGS)

    fun wifiIntent(): Intent = Xiaomi.wifiIntent(ctx) ?: Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)

    /** HDMI ports from the TV input framework; devices behind a port (CEC) are left out. */
    private fun androidInputs(): List<Bravia.Input> {
        val tim = ctx.getSystemService(TvInputManager::class.java) ?: return emptyList()
        return try {
            tim.tvInputList.filter { it.type == TvInputInfo.TYPE_HDMI && it.parentId == null && !Xiaomi.hidden(it.id) }.map {
                val title = it.loadLabel(ctx).toString()
                Bravia.Input(
                    Xiaomi.inputUri(ctx, it.id) ?: TvContract.buildChannelUriForPassthroughInput(it.id).toString(),
                    title,
                    it.loadCustomLabel(ctx)?.toString()?.takeIf { l -> l != title }.orEmpty(),
                    tim.getInputState(it.id) == TvInputManager.INPUT_STATE_CONNECTED,
                )
            }.sortedBy { it.title }
        } catch (e: Exception) {
            Log.w("TvControls", "inputs failed", e)
            emptyList()
        }
    }
}

/**
 * TCL / FFalcon China firmware.
 *
 * - Inputs: the firmware registers no TvInputService at all (`dumpsys tv_input` has an empty
 *   inputMap), so `TvInputManager.tvInputList` is empty and the standard passthrough URI has no
 *   handler. LiveTV owns the HDMI ports through its own Unified Source stack, so switching means
 *   opening LiveTV's source picker.
 * - Brightness: TCL ignores the standard `screen_brightness` key and has no sysfs backlight node;
 *   the backlight lives behind `com.tcl.tv.pq` / TPictureManager, so the slider stays hidden and
 *   the backlight is reached through TCL's settings app ([settingsIntent]).
 * - Settings: nothing answers `android.settings.SETTINGS`; TCL's settings app opens with ACTION_MAIN.
 */
private object Tcl {
    const val URI_PREFIX = "tcl-input://"
    private const val SETTINGS_PKG = "com.tcl.settings"
    private const val LIVE_TV = "com.tcl.tv"

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    /**
     * One entry, not four: the firmware exposes no way to address a port directly, so claiming a
     * per-port list would be a lie. This opens LiveTV, which shows its own picker
     * (TV / HDMI1(ARC/eARC) / HDMI2-4 / AV / 投屏) for the user to choose from.
     */
    fun inputs(ctx: Context): List<Bravia.Input> =
        if (installed(ctx, LIVE_TV)) listOf(Bravia.Input(URI_PREFIX, "Sources TCL", "", false)) else emptyList()

    fun setInput(ctx: Context) {
        start(ctx, Intent(Intent.ACTION_VIEW).setComponent(ComponentName(LIVE_TV, "$LIVE_TV.TVActivity")))
    }

    fun settingsIntent(ctx: Context): Intent? =
        if (installed(ctx, SETTINGS_PKG))
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(SETTINGS_PKG, "$SETTINGS_PKG.ui.MainActivity"))
        else null

    private fun start(ctx: Context, i: Intent): Boolean = runCatching {
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse { Log.w("TvControls", "TCL start failed for $i", it); false }
}

/**
 * Xiaomi / Redmi China firmware (PatchWall, HyperOS TV). From Aerial's device notes
 * (github.com/pengchujin/aerial-tv, docs/devices/xiaomi-*.md):
 *
 * - Backlight: Settings.Global "picture_backlight", 0–100, applied by the firmware's TV menu
 *   service as soon as it changes. Writing it needs WRITE_SECURE_SETTINGS (granted with the guard).
 * - Inputs: the generic passthrough intent has no handler; Xiaomi's player switches with
 *   EXTSRC_PLAY, where MediaTek hardware ids HW5.. map onto source ids 23... MiTV-MFTR0 also
 *   registers an HDMI input HW8 with no connector behind it.
 * - Settings and Wi-Fi: Xiaomi's own settings app.
 */
private object Xiaomi {
    const val URI_PREFIX = "xiaomi-input:"
    private const val PLAYER = "com.xiaomi.mitv.tvplayer"
    private const val SETTINGS = "com.xiaomi.mitv.settings"
    private const val BACKLIGHT = "picture_backlight"

    private fun hw(id: String): Int? = Regex("""/HW(\d+)$""").find(id)?.groupValues?.get(1)?.toIntOrNull()

    private fun installed(ctx: Context, pkg: String) =
        runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

    fun hidden(id: String) = Build.MODEL in setOf("MiTV_MFTR0", "MiTV-MFTR0") && hw(id) == 8

    fun inputUri(ctx: Context, id: String): String? =
        if (installed(ctx, PLAYER)) hw(id)?.let { "$URI_PREFIX${it + 18}" } else null

    fun setInput(ctx: Context, uri: String) {
        val n = uri.removePrefix(URI_PREFIX).toIntOrNull() ?: return
        runCatching {
            ctx.startActivity(Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(PLAYER, "$PLAYER.ExternalSourceActivity"))
                .putExtra("input", n)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("TvControls", "Xiaomi input $n failed", it) }
    }

    fun brightness(ctx: Context): Bravia.Level? {
        val r = ctx.contentResolver
        if (Settings.Global.getString(r, BACKLIGHT) == null || !Guard.hasSecureSettings(ctx)) return null
        return Bravia.Level(Settings.Global.getInt(r, BACKLIGHT, 50), 0, 100)
    }

    fun setBrightness(ctx: Context, v: Int) =
        runCatching { Settings.Global.putInt(ctx.contentResolver, BACKLIGHT, v.coerceIn(0, 100)) }.isSuccess

    fun settingsIntent(ctx: Context) = resolvable(ctx, Intent("com.xiaomi.mitv.settings.SETTINGS")
        .setComponent(ComponentName(SETTINGS, "$SETTINGS.entry.MainActivity")))

    fun wifiIntent(ctx: Context) = resolvable(ctx, Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS")
        .setComponent(ComponentName(SETTINGS, "$SETTINGS.wifi.MitvWifiActivity")))

    /** PackageManager checks an explicit component; Intent.resolveActivity would return it unchecked. */
    private fun resolvable(ctx: Context, i: Intent): Intent? = if (ctx.packageManager.resolveActivity(i, 0) != null) i else null
}
