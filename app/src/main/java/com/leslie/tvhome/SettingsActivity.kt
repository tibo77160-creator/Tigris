package com.leslie.tvhome

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * tvOS-style settings: a large icon on the left, a list of rows on the
 * right. Sub-pages (keep list, hidden apps) reuse the same list.
 */
class SettingsActivity : Activity() {
    companion object {
        const val SONY_DREAM = "com.sony.dtv.braviashow/com.sony.dtv.braviashow.BraviaDayDreamService"
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepo
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var scroll: ScrollView
    private var page = Page.MAIN

    private enum class Page { MAIN, KEEP, HIDDEN, REMOVED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(16, 16, 18))
        }
        val left = FrameLayout(this)
        left.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_gear)
            setColorFilter(Color.argb(200, 255, 255, 255))
        }, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(48), dp(56), dp(24))
        }
        heading = text(26f, bold = true)
        right.addView(heading, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22); marginStart = dp(16) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(40))
            addView(list)
        }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1.15f))
        setContentView(root)
        show(Page.MAIN)
    }

    private fun show(p: Page, focusIndex: Int = 0) {
        page = p
        list.removeAllViews()
        when (p) {
            Page.MAIN -> main()
            Page.KEEP -> appPicker("Conserver lors du nettoyage", prefs.keep) { pkg, on ->
                prefs.keep = if (on) prefs.keep + pkg else prefs.keep - pkg
            }
            Page.HIDDEN -> appPicker("Applis masquées", prefs.hidden, includeHidden = true) { pkg, on ->
                prefs.hidden = if (on) prefs.hidden + pkg else prefs.hidden - pkg
            }
            Page.REMOVED -> removedApps()
        }
        list.post { list.getChildAt(focusIndex.coerceAtMost(list.childCount - 1))?.requestFocus() }
    }

    private fun main() {
        heading.text = "Réglages de l'accueil"
        row("Libère la mémoire automatiquement en veille", { onOff(prefs.cleanOnStandby) }) { prefs.cleanOnStandby = !prefs.cleanOnStandby }
        row("Libérer la mémoire régulièrement", { if (prefs.cleanIntervalHours == 0) "Désactivé" else "Toutes les ${prefs.cleanIntervalHours} h" }) {
            val opts = listOf(0, 2, 4, 8)
            prefs.cleanIntervalHours = opts[(opts.indexOf(prefs.cleanIntervalHours) + 1) % opts.size]
            KeeperService.start(this, KeeperService.ACTION_RESCHEDULE)
        }
        row("Bouton marche de la télécommande", { if (prefs.shutdownOnPowerKey) "Extinction complète" else "Veille" }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, "Nécessite le débogage local autorisé", Toast.LENGTH_LONG).show()
        }
        row("Applis conservées lors du nettoyage", { "${prefs.keep.size}" }) { show(Page.KEEP) }
        row("Libérer la mémoire maintenant", { "${Cleaner.availMb(this)} Mo disponibles" }) { v ->
            v.text = "Libération…"
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread { v.text = "${r.freedMb} Mo libérés" }
            }.start()
        }
        row("Applis masquées", { "${prefs.hidden.size}" }) { show(Page.HIDDEN) }
        row("Applis préinstallées supprimées", { "${prefs.removed.size}" }) { show(Page.REMOVED) }
        row("Fond d'écran", {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
                if (prefs.background == Wallpapers.MINE) it else "$it (change automatiquement)"
            }
        }) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row("Changement de photo", { minutesLabel(prefs.wallMinutes) }) {
            val opts = listOf(15, 30, 60, 180, 1440)
            prefs.wallMinutes = opts[(opts.indexOf(prefs.wallMinutes) + 1) % opts.size]
        }
        row("Mettre à jour les photos", { "${Wallpapers(this, Wallpapers.sourceFor(prefs.background)).files().size} photos" }) { v ->
            v.text = "Téléchargement…"
            Thread {
                val w = Wallpapers(applicationContext, Wallpapers.sourceFor(prefs.background))
                w.refresh(force = true)
                runOnUiThread { v.text = "${w.files().size} photos" }
            }.start()
        }
        row("Cadre photo quand la TV est inactive", { onOff(prefs.frameScreensaver) }) { v ->
            val on = !prefs.frameScreensaver
            val current = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()
            val mine = "$packageName/${PhotoDream::class.java.name}"
            // Whatever the TV had before goes back when the frame is turned off (Sony's own if unknown).
            if (on && !current.startsWith(packageName)) prefs.prevScreensaver = current
            val comp = if (on) mine else prefs.prevScreensaver.ifEmpty { SONY_DREAM }
            v.text = "Réglage…"
            Thread {
                val ok = putSecure("screensaver_components", comp) && putSecure("screensaver_enabled", "1")
                if (ok) prefs.frameScreensaver = on
                runOnUiThread { v.text = if (ok) onOff(prefs.frameScreensaver) else "Autoriser d'abord le débogage local" }
            }.start()
        }
        row("Contenu de l'économiseur", { if (prefs.saverMode == Prefs.SAVER_AERIAL) "Vidéos aériennes Apple TV" else "Photos" }) {
            prefs.saverMode = if (prefs.saverMode == Prefs.SAVER_AERIAL) Prefs.SAVER_PHOTOS else Prefs.SAVER_AERIAL
        }
        row("Délai avant le cadre photo", { minutesLabel(idleMinutes()) }) { v ->
            val opts = listOf(5, 10, 15, 30)
            val next = opts[(opts.indexOf(idleMinutes()) + 1) % opts.size]
            v.text = "Réglage…"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "settings put system screen_off_timeout ${next * 60_000}") != null
                runOnUiThread { v.text = if (ok) minutesLabel(next) else "Autoriser d'abord le débogage local" }
            }.start()
        }
        row("Durée de chaque photo", { "${prefs.frameSeconds} s" }) {
            val opts = listOf(15, 30, 60, 120)
            prefs.frameSeconds = opts[(opts.indexOf(prefs.frameSeconds) + 1) % opts.size]
        }
        row("Débogage local", { if (prefs.adbAuthorized) "Autorisé" else "Non autorisé, appuyez sur OK pour autoriser" }) { v ->
            v.text = "Dans la fenêtre, choisissez « Toujours autoriser »"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) "Autorisé" else "Non autorisé, appuyez sur OK pour réessayer" }
            }.start()
        }
        // Only where the TV keeps Home for its own launcher; on Sony or stock Android TV Tigris is the real home.
        if (Guard.needed(this)) row("Garde de l'accueil", { if (!prefs.guard) "Non" else if (HomeGuardService.isRunning) "Oui" else "Oui (inactif)" }) { v ->
            val on = !prefs.guard
            v.text = "Réglage…"
            Thread {
                val ok = (Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)) && Guard.setEnabled(applicationContext, on)
                runOnUiThread {
                    v.text = if (!ok) "Autoriser d'abord le débogage local" else if (on) "Oui" else "Non"
                    if (ok && on) Toast.makeText(this, "Si la touche Home ramène l'accueil d'origine, cet écran revient automatiquement", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        if (Guard.isXiaomi() || prefs.keepAdb) row("Garder le débogage ADB actif", { onOff(prefs.keepAdb) }) { v ->
            val on = !prefs.keepAdb
            v.text = "Réglage…"
            Thread {
                val ok = !on || Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)
                if (ok) { prefs.keepAdb = on; Guard.ensureAdb(applicationContext) }
                runOnUiThread { v.text = if (ok) onOff(prefs.keepAdb) else "Autoriser d'abord le débogage local" }
            }.start()
        }
        row("Paramètres de la TV", { "" }) { runCatching { startActivity(TvControls(this).settingsIntent()) } }
        row("À propos", { "Accueil " + packageManager.getPackageInfo(packageName, 0).versionName }) {}
    }

    private fun appPicker(title: String, selected: Set<String>, includeHidden: Boolean = false, toggle: (String, Boolean) -> Unit) {
        heading.text = title
        val apps = if (includeHidden) repo.allApps().sortedBy { it.label } else repo.allApps().filter { !repo.isSystem(it.pkg) }.sortedBy { it.label }
        val sel = selected.toMutableSet()
        for (a in apps) {
            row(a.label, { if (a.pkg in sel) "✓" else "" }) { v ->
                val on = a.pkg !in sel
                if (on) sel += a.pkg else sel -= a.pkg
                toggle(a.pkg, on)
                v.text = if (on) "✓" else ""
            }
        }
    }

    /** Preinstalled apps deleted from the long-press menu; selecting one reinstalls it from the system partition. */
    private fun removedApps() {
        heading.text = "Applis préinstallées supprimées"
        if (prefs.removed.isEmpty()) row("Aucune appli supprimée", { "" }) {}
        for (pkg in prefs.removed.sorted()) {
            val label = try {
                packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(packageManager).toString()
            } catch (e: Exception) { pkg }
            row(label, { "Restaurer" }) { v ->
                v.text = "Restauration…"
                Thread {
                    val ok = LocalAdb.shell(applicationContext, "cmd package install-existing $pkg")?.contains("installed") == true
                    if (ok) prefs.removed = prefs.removed - pkg
                    runOnUiThread { v.text = if (ok) "Restaurée" else "Échec de la restauration" }
                }.start()
            }
        }
    }

    /** One settings row; [value] is re-read after each click. */
    private fun row(label: String, value: () -> String, onClick: (TextView) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(20), 0, dp(20), 0)
        }
        val l = text(17f, bold = true).apply { text = label }
        val v = text(16f, Color.argb(150, 255, 255, 255)).apply { text = value() }
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v)
        val idle = rounded(Color.TRANSPARENT, dp(12).toFloat())
        val focus = rounded(Color.WHITE, dp(12).toFloat())
        r.background = idle
        r.setOnFocusChangeListener { _, f ->
            r.background = if (f) focus else idle
            l.setTextColor(if (f) Color.BLACK else Color.WHITE)
            v.setTextColor(if (f) Color.argb(150, 0, 0, 0) else Color.argb(150, 255, 255, 255))
            r.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f)
                .translationZ(if (f) dp(8).toFloat() else 0f).setDuration(150).start()
        }
        r.setOnClickListener {
            onClick(v)
            if (page == Page.MAIN && !v.text.endsWith("…") && !v.text.startsWith("Dans la fenêtre")) v.text = value()
        }
        list.addView(r, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) })
    }

    private fun onOff(b: Boolean) = if (b) "Oui" else "Non"

    /** A secure setting, written directly once WRITE_SECURE_SETTINGS is granted, otherwise through local adb. Blocking. */
    private fun putSecure(key: String, value: String): Boolean =
        if (Guard.hasSecureSettings(this)) runCatching { Settings.Secure.putString(contentResolver, key, value) }.getOrDefault(false)
        else LocalAdb.shell(applicationContext, "settings put secure $key $value") != null

    private fun minutesLabel(m: Int) = when {
        m >= 1440 -> "Chaque jour"
        m >= 60 -> "${m / 60} h"
        else -> "$m min"
    }

    /** On Android TV the screensaver starts after the "screen off" timeout. */
    private fun idleMinutes() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) {
            val back = when (page) { Page.KEEP -> 3; Page.HIDDEN -> 5; else -> 6 }
            show(Page.MAIN, back)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) true else super.onKeyDown(keyCode, event)
}
package com.leslie.tvhome

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * tvOS-style settings: a large icon on the left, a list of rows on the
 * right. Sub-pages (keep list, hidden apps) reuse the same list.
 */
class SettingsActivity : Activity() {
    companion object {
        const val SONY_DREAM = "com.sony.dtv.braviashow/com.sony.dtv.braviashow.BraviaDayDreamService"
    }

    private lateinit var prefs: Prefs
    private lateinit var repo: AppRepo
    private lateinit var list: LinearLayout
    private lateinit var heading: TextView
    private lateinit var scroll: ScrollView
    private var page = Page.MAIN

    private enum class Page { MAIN, KEEP, HIDDEN, REMOVED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        repo = AppRepo(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.rgb(16, 16, 18))
        }
        val left = FrameLayout(this)
        left.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_gear)
            setColorFilter(Color.argb(200, 255, 255, 255))
        }, FrameLayout.LayoutParams(dp(170), dp(170), Gravity.CENTER))
        root.addView(left, LinearLayout.LayoutParams(0, -1, 1f))

        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(48), dp(56), dp(24))
        }
        heading = text(26f, bold = true)
        right.addView(heading, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(22); marginStart = dp(16) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipToPadding = false
            setPadding(dp(10), dp(6), dp(10), dp(40))
            addView(list)
        }
        right.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(right, LinearLayout.LayoutParams(0, -1, 1.15f))
        setContentView(root)
        show(Page.MAIN)
    }

    private fun show(p: Page, focusIndex: Int = 0) {
        page = p
        list.removeAllViews()
        when (p) {
            Page.MAIN -> main()
            Page.KEEP -> appPicker("释放内存时保留", prefs.keep) { pkg, on ->
                prefs.keep = if (on) prefs.keep + pkg else prefs.keep - pkg
            }
            Page.HIDDEN -> appPicker("隐藏的 App", prefs.hidden, includeHidden = true) { pkg, on ->
                prefs.hidden = if (on) prefs.hidden + pkg else prefs.hidden - pkg
            }
            Page.REMOVED -> removedApps()
        }
        list.post { list.getChildAt(focusIndex.coerceAtMost(list.childCount - 1))?.requestFocus() }
    }

    private fun main() {
        heading.text = "主屏幕设置"
        row("待机时自动释放内存", { onOff(prefs.cleanOnStandby) }) { prefs.cleanOnStandby = !prefs.cleanOnStandby }
        row("定时释放内存", { if (prefs.cleanIntervalHours == 0) "关闭" else "每 ${prefs.cleanIntervalHours} 小时" }) {
            val opts = listOf(0, 2, 4, 8)
            prefs.cleanIntervalHours = opts[(opts.indexOf(prefs.cleanIntervalHours) + 1) % opts.size]
            KeeperService.start(this, KeeperService.ACTION_RESCHEDULE)
        }
        row("按遥控器电源键时", { if (prefs.shutdownOnPowerKey) "彻底关机" else "待机" }) {
            prefs.shutdownOnPowerKey = !prefs.shutdownOnPowerKey
            if (prefs.shutdownOnPowerKey && !prefs.adbAuthorized)
                Toast.makeText(this, "需要先授权本机调试才会生效", Toast.LENGTH_LONG).show()
        }
        row("释放内存时保留的 App", { "${prefs.keep.size} 个" }) { show(Page.KEEP) }
        row("立即释放内存", { "可用 ${Cleaner.availMb(this)} MB" }) { v ->
            v.text = "正在释放…"
            Thread {
                val r = Cleaner.clean(applicationContext)
                runOnUiThread { v.text = "已释放 ${r.freedMb} MB" }
            }.start()
        }
        row("隐藏的 App", { "${prefs.hidden.size} 个" }) { show(Page.HIDDEN) }
        row("已删除的预装 App", { "${prefs.removed.size} 个" }) { show(Page.REMOVED) }
        row("背景", {
            Backgrounds.THEMES[prefs.background]?.first ?: Wallpapers.label(Wallpapers.sourceFor(prefs.background)).let {
                if (prefs.background == Wallpapers.MINE) it else "$it（自动更换）"
            }
        }) { startActivity(Intent(this, WallpaperActivity::class.java)) }
        row("照片更换频率", { minutesLabel(prefs.wallMinutes) }) {
            val opts = listOf(15, 30, 60, 180, 1440)
            prefs.wallMinutes = opts[(opts.indexOf(prefs.wallMinutes) + 1) % opts.size]
        }
        row("立即更新照片", { "已有 ${Wallpapers(this, Wallpapers.sourceFor(prefs.background)).files().size} 张" }) { v ->
            v.text = "正在下载…"
            Thread {
                val w = Wallpapers(applicationContext, Wallpapers.sourceFor(prefs.background))
                w.refresh(force = true)
                runOnUiThread { v.text = "已有 ${w.files().size} 张" }
            }.start()
        }
        row("电视空闲时显示相框", { onOff(prefs.frameScreensaver) }) { v ->
            val on = !prefs.frameScreensaver
            val current = Settings.Secure.getString(contentResolver, "screensaver_components").orEmpty()
            val mine = "$packageName/${PhotoDream::class.java.name}"
            // Whatever the TV had before goes back when the frame is turned off (Sony's own if unknown).
            if (on && !current.startsWith(packageName)) prefs.prevScreensaver = current
            val comp = if (on) mine else prefs.prevScreensaver.ifEmpty { SONY_DREAM }
            v.text = "正在设置…"
            Thread {
                val ok = putSecure("screensaver_components", comp) && putSecure("screensaver_enabled", "1")
                if (ok) prefs.frameScreensaver = on
                runOnUiThread { v.text = if (ok) onOff(prefs.frameScreensaver) else "需要先授权本机调试" }
            }.start()
        }
        row("屏保内容", { if (prefs.saverMode == Prefs.SAVER_AERIAL) "Apple TV 航拍视频" else "照片" }) {
            prefs.saverMode = if (prefs.saverMode == Prefs.SAVER_AERIAL) Prefs.SAVER_PHOTOS else Prefs.SAVER_AERIAL
        }
        row("空闲多久显示相框", { minutesLabel(idleMinutes()) }) { v ->
            val opts = listOf(5, 10, 15, 30)
            val next = opts[(opts.indexOf(idleMinutes()) + 1) % opts.size]
            v.text = "正在设置…"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "settings put system screen_off_timeout ${next * 60_000}") != null
                runOnUiThread { v.text = if (ok) minutesLabel(next) else "需要先授权本机调试" }
            }.start()
        }
        row("相框每张照片停留", { "${prefs.frameSeconds} 秒" }) {
            val opts = listOf(15, 30, 60, 120)
            prefs.frameSeconds = opts[(opts.indexOf(prefs.frameSeconds) + 1) % opts.size]
        }
        row("本机调试授权", { if (prefs.adbAuthorized) "已授权" else "未授权，按确认键授权" }) { v ->
            v.text = "请在弹窗中选择“始终允许”"
            Thread {
                val ok = LocalAdb.shell(applicationContext, "echo ok", 60_000) != null
                runOnUiThread { v.text = if (ok) "已授权" else "未授权，按确认键重试" }
            }.start()
        }
        // Only where the TV keeps Home for its own launcher; on Sony or stock Android TV Tigris is the real home.
        if (Guard.needed(this)) row("桌面守护", { if (!prefs.guard) "关" else if (HomeGuardService.isRunning) "开" else "开（未运行）" }) { v ->
            val on = !prefs.guard
            v.text = "正在设置…"
            Thread {
                val ok = (Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)) && Guard.setEnabled(applicationContext, on)
                runOnUiThread {
                    v.text = if (!ok) "需要先授权本机调试" else if (on) "开" else "关"
                    if (ok && on) Toast.makeText(this, "按主页键回到原厂桌面时会自动回到这里", Toast.LENGTH_LONG).show()
                }
            }.start()
        }
        if (Guard.isXiaomi() || prefs.keepAdb) row("保持 ADB 调试开启", { onOff(prefs.keepAdb) }) { v ->
            val on = !prefs.keepAdb
            v.text = "正在设置…"
            Thread {
                val ok = !on || Guard.hasSecureSettings(this) || Guard.grantViaLocalAdb(applicationContext)
                if (ok) { prefs.keepAdb = on; Guard.ensureAdb(applicationContext) }
                runOnUiThread { v.text = if (ok) onOff(prefs.keepAdb) else "需要先授权本机调试" }
            }.start()
        }
        row("系统设置", { "" }) { runCatching { startActivity(TvControls(this).settingsIntent()) } }
        row("关于", { "主屏幕 " + packageManager.getPackageInfo(packageName, 0).versionName }) {}
    }

    private fun appPicker(title: String, selected: Set<String>, includeHidden: Boolean = false, toggle: (String, Boolean) -> Unit) {
        heading.text = title
        val apps = if (includeHidden) repo.allApps().sortedBy { it.label } else repo.allApps().filter { !repo.isSystem(it.pkg) }.sortedBy { it.label }
        val sel = selected.toMutableSet()
        for (a in apps) {
            row(a.label, { if (a.pkg in sel) "✓" else "" }) { v ->
                val on = a.pkg !in sel
                if (on) sel += a.pkg else sel -= a.pkg
                toggle(a.pkg, on)
                v.text = if (on) "✓" else ""
            }
        }
    }

    /** Preinstalled apps deleted from the long-press menu; selecting one reinstalls it from the system partition. */
    private fun removedApps() {
        heading.text = "已删除的预装 App"
        if (prefs.removed.isEmpty()) row("没有已删除的 App", { "" }) {}
        for (pkg in prefs.removed.sorted()) {
            val label = try {
                packageManager.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES).loadLabel(packageManager).toString()
            } catch (e: Exception) { pkg }
            row(label, { "恢复" }) { v ->
                v.text = "正在恢复…"
                Thread {
                    val ok = LocalAdb.shell(applicationContext, "cmd package install-existing $pkg")?.contains("installed") == true
                    if (ok) prefs.removed = prefs.removed - pkg
                    runOnUiThread { v.text = if (ok) "已恢复" else "恢复失败" }
                }.start()
            }
        }
    }

    /** One settings row; [value] is re-read after each click. */
    private fun row(label: String, value: () -> String, onClick: (TextView) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(20), 0, dp(20), 0)
        }
        val l = text(17f, bold = true).apply { text = label }
        val v = text(16f, Color.argb(150, 255, 255, 255)).apply { text = value() }
        r.addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        r.addView(v)
        val idle = rounded(Color.TRANSPARENT, dp(12).toFloat())
        val focus = rounded(Color.WHITE, dp(12).toFloat())
        r.background = idle
        r.setOnFocusChangeListener { _, f ->
            r.background = if (f) focus else idle
            l.setTextColor(if (f) Color.BLACK else Color.WHITE)
            v.setTextColor(if (f) Color.argb(150, 0, 0, 0) else Color.argb(150, 255, 255, 255))
            r.animate().scaleX(if (f) 1.03f else 1f).scaleY(if (f) 1.03f else 1f)
                .translationZ(if (f) dp(8).toFloat() else 0f).setDuration(150).start()
        }
        r.setOnClickListener {
            onClick(v)
            if (page == Page.MAIN && !v.text.startsWith("正在") && !v.text.startsWith("请在")) v.text = value()
        }
        list.addView(r, LinearLayout.LayoutParams(-1, dp(54)).apply { bottomMargin = dp(6) })
    }

    private fun onOff(b: Boolean) = if (b) "开" else "关"

    /** A secure setting, written directly once WRITE_SECURE_SETTINGS is granted, otherwise through local adb. Blocking. */
    private fun putSecure(key: String, value: String): Boolean =
        if (Guard.hasSecureSettings(this)) runCatching { Settings.Secure.putString(contentResolver, key, value) }.getOrDefault(false)
        else LocalAdb.shell(applicationContext, "settings put secure $key $value") != null

    private fun minutesLabel(m: Int) = when {
        m >= 1440 -> "每天"
        m >= 60 -> "${m / 60} 小时"
        else -> "$m 分钟"
    }

    /** On Android TV the screensaver starts after the "screen off" timeout. */
    private fun idleMinutes() = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, 600_000) / 60_000

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) {
            val back = when (page) { Page.KEEP -> 3; Page.HIDDEN -> 5; else -> 6 }
            show(Page.MAIN, back)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK && page != Page.MAIN) true else super.onKeyDown(keyCode, event)
}
