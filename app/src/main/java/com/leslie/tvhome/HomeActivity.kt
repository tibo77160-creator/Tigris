package com.leslie.tvhome

import android.app.Activity
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.TransitionDrawable
import android.graphics.Rect
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * tvOS 26 style home: soft colour gradient, date and a large thin clock
 * top-left, Wi-Fi and Control Center buttons top-right, then the app grid.
 */
class HomeActivity : Activity(), TileAdapter.Listener {
    companion object {
        const val SHELF_SECONDS = 8
        private const val SHELF_BAR_DP = 260
        private const val TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private const val EXTRA_GUARD = "com.leslie.tvhome.GUARD"

        /**
         * How the home guard and the boot receiver bring the home screen back: the same task, with
         * no HOME category. Where Tigris is not the system home, that category made Android open a
         * second, home-type task with a fresh screen every time (Aerial, Xiaomi MiTV-MFTR0).
         */
        fun bringBack(ctx: Context): Intent = Intent(ctx, HomeActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            .putExtra(EXTRA_GUARD, true)
    }

    private lateinit var root: FrameLayout
    private lateinit var bg: ImageView
    private lateinit var header: FrameLayout
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView
    private lateinit var netButton: IconButton
    private lateinit var ccButton: IconButton
    private lateinit var grid: RecyclerView
    private lateinit var adapter: TileAdapter
    private lateinit var repo: AppRepo
    private lateinit var prefs: Prefs
    private lateinit var control: ControlCenter
    private val tv by lazy { TvControls(this) }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val sheets = ArrayList<Sheet>()

    private val cols = 6
    private var tileW = 0
    private var tileH = 0
    private var rowPitch = 0
    private var firstRowY = 0
    /** Where the focused row sits once the grid is expanded below the dock. */
    private var expandedRowY = 0

    private var focusedPos = 0
    private var firstFocus = true
    /** Grid scroll position; RecyclerView's own estimate is unreliable for grids. */
    private var scrollY = 0
    private var shownBackground: String? = null
    @Volatile private var refreshing = false
    private var bgPhoto: android.graphics.drawable.Drawable? = null
    private var sw = 0
    private var sh = 0

    /** Liquid Glass slab behind the first row, the "dock". */
    private lateinit var dock: ImageView
    private val dockRect = Rect()
    private var dockRadius = 0f
    /** The glass baked over the home picture, kept for when the shelf goes away. */
    @Volatile private var homeDock: Bitmap? = null
    private var dockShown: Drawable? = null

    /** Top Shelf: the focused dock app's series in progress, as cards above the dock (Netflix / Apple style). */
    private lateinit var shelfRow: ShelfRow
    /** Up from the dock moves into the cards: Left/Right choose, OK plays, Down or Back returns. */
    private var shelfFocus = false
    /** Bands at the screen edges in the focused app's colour, which Ambilight ("follow video") picks up. */
    private lateinit var edges: EdgeGlow
    private val net = Executors.newFixedThreadPool(2)
    private var shelfPkg: String? = null
    private var pendingShelf: String? = null
    private var shelfShown = false
    private val shelfItems get() = shelfRow.items
    private val shelfShow = Runnable { pendingShelf?.let { showShelf(it) } }
    /** Kept for the lifecycle hooks; the cards no longer rotate on their own. */
    private val shelfAdvance = Runnable {}

    /** Position being dragged in "edit home screen" mode, or -1. */
    private var moving = -1

    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pkg = i.data?.schemeSpecificPart
            repo.invalidate(pkg)
            reload(pkg)
        }
    }

    /**
     * Xiaomi shows its own screensaver (with ads) after system/screen_saver_timeout instead of
     * Android's, so the photo frame starts 15 s ahead of it when the home screen sits idle, and
     * keeps the screen on so Xiaomi's does not follow. Elsewhere Android's screensaver applies.
     */
    private val idleFrame = Runnable {
        if (hasWindowFocus() && sheets.isEmpty() && !control.isOpen) openFrame()
    }

    private fun firmwareSaverDelay(): Long? {
        val t = runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull() ?: return null
        return if (t > 0) maxOf(30_000L, t - 15_000L) else null
    }

    private fun restartIdle() {
        main.removeCallbacks(idleFrame)
        firmwareSaverDelay()?.let { main.postDelayed(idleFrame, it) }
    }

    fun openFrame() {
        startActivity(Intent(this, FrameActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private val ticker = object : Runnable {
        override fun run() {
            updateClock()
            applyBackground()
            main.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Backgrounds.app = applicationContext
        if (prefs.setupVersion < 1) {
            // First start of this version: the household's dock order and the dark mountains.
            prefs.order = emptyList()
            prefs.background = "rockies"
            prefs.setupVersion = 1
        }
        repo = AppRepo(this)
        buildViews()
        control = ControlCenter(this, root)
        reload()
        registerReceiver(pkgReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        KeeperService.start(this)
        if (!prefs.adbAuthorized) main.postDelayed({ askAdbAuthorization() }, 1500)
        // The Top Shelf reads what apps publish on Android TV (series in progress): asked once.
        if (checkSelfPermission(TV_LISTINGS) != PackageManager.PERMISSION_GRANTED)
            runCatching { requestPermissions(arrayOf(TV_LISTINGS), 7) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Shelf.invalidate()
    }

    override fun onDestroy() {
        unregisterReceiver(pkgReceiver)
        io.shutdown()
        net.shutdown()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ layout

    private fun buildViews() {
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val match = { FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        bg = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(bg, match())

        val dm = resources.displayMetrics
        sw = dm.widthPixels
        sh = dm.heightPixels
        // Proportions measured from a 1920×1080 tvOS 26 screenshot.
        val side = (sw * 0.0583f).toInt()          // 112 px
        val gap = (sw * 0.0292f).toInt()           // 56 px
        tileW = (sw - side * 2 - gap * (cols - 1)) / cols
        tileH = tileW * 9 / 16
        rowPitch = (sh * 0.2463f).toInt()          // 266 px
        // The dock sits at the bottom; only the top edge of the next row peeks out below it.
        firstRowY = sh - rowPitch - (sh * 0.055f).toInt()   // 755 px
        expandedRowY = (sh * 0.36f).toInt()

        // The dock hugs the first row: a focused tile (scaled 1.115) nearly touches its edges.
        val pad = dp(15)
        dockRect.set(side - pad, firstRowY - pad, sw - side + pad, firstRowY + tileH + pad)
        dockRadius = dp(11) + pad.toFloat()
        buildShelf(side)
        dock = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY }
        root.addView(dock, FrameLayout.LayoutParams(dockRect.width(), dockRect.height()).apply {
            leftMargin = dockRect.left; topMargin = dockRect.top
        })

        // Header: date + clock on the left, buttons on the right. It scrolls with the grid.
        header = FrameLayout(this).apply { clipChildren = false; clipToPadding = false }
        dateText = text(17f, Color.argb(220, 255, 255, 255), weight = 500).apply { setShadowLayer(dp(8).toFloat(), 0f, 0f, Color.argb(90, 0, 0, 0)) }
        clockText = TextView(this).apply {
            setTextColor(Color.WHITE)
            typeface = Fonts.inter(this@HomeActivity, 300)
            textSize = 44f
            includeFontPadding = false
            letterSpacing = -0.02f
        }
        // Small clock with the date beside it in the top-left corner: the space below is for the cards.
        val clockRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(clockText)
            addView(dateText, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16); bottomMargin = dp(7) })
        }
        header.addView(clockRow, FrameLayout.LayoutParams(-2, -2).apply {
            leftMargin = side - dp(2); topMargin = (sh * 0.05f).toInt()
        })
        val icons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            // The padding is the room a focused (enlarged) disc grows into; don't clip it away.
            clipToPadding = false
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        netButton = IconButton(this, R.drawable.ic_wifi) {
            runCatching { startActivity(tv.wifiIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        ccButton = IconButton(this, R.drawable.ic_toggles) { control.open() }
        icons.addView(netButton, LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
        icons.addView(ccButton, LinearLayout.LayoutParams(dp(36), dp(36)))
        header.addView(icons, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.TOP).apply {
            rightMargin = side - dp(16); topMargin = (sh * 0.083f - dp(26)).toInt()
        })
        listOf(netButton, ccButton).forEach { b ->
            b.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) { focusAt(focusedPos); true } else false
            }
        }
        netButton.nextFocusRightId = View.generateViewId().also { ccButton.id = it }
        ccButton.nextFocusLeftId = View.generateViewId().also { netButton.id = it }
        root.addView(header, match())

        adapter = TileAdapter(repo, tileW, tileH, this).apply { setHasStableIds(true); dockCount = cols }
        grid = RecyclerView(this).apply {
            clipChildren = false
            clipToPadding = false
            itemAnimator?.changeDuration = 0
            // Room below the last row so it can scroll up to the expanded position.
            setPadding(side, firstRowY, side, sh - expandedRowY - tileH)
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            overScrollMode = View.OVER_SCROLL_NEVER
            setItemViewCacheSize(30)
        }
        grid.layoutManager = object : GridLayoutManager(this, cols) {
            override fun requestChildRectangleOnScreen(
                parent: RecyclerView, child: View, rect: Rect, immediate: Boolean, focusedChildVisible: Boolean,
            ): Boolean {
                // The dock row rests at the bottom; any other row is brought up to the expanded position.
                val row = getPosition(child) / cols
                val wantedOffset = if (row == 0) 0 else firstRowY + row * rowPitch - expandedRowY
                val dy = wantedOffset - scrollY
                if (dy != 0) parent.smoothScrollBy(0, dy, Motion.ease, 360)
                return true
            }
        }
        grid.adapter = adapter
        // Tiles update in place; the default cross-fade would swap in a second view and drop focus.
        (grid.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        grid.addItemDecoration(object : RecyclerView.ItemDecoration() {
            override fun getItemOffsets(out: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
                val col = parent.getChildAdapterPosition(view) % cols
                out.left = col * gap / cols
                out.right = gap - (col + 1) * gap / cols
                out.bottom = rowPitch - tileH
            }
        })
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                // Measured from the rows themselves: layouts and jumps (reloads, scrollToPosition)
                // move the grid with dy = 0, so a running sum of dy drifts away from the truth.
                scrollY = gridOffset() ?: (scrollY + dy)
                val off = scrollY.toFloat()
                header.translationY = -off
                header.alpha = (1f - off / (rowPitch * 1.6f)).coerceIn(0f, 1f)
                // The glass was baked for its resting place, so it fades out quickly once it moves.
                dock.translationY = -off
                dock.alpha = (1f - off / (rowPitch * 0.7f)).coerceIn(0f, 1f)
            }
        })
        root.addView(grid, match())
        edges = EdgeGlow(this)
        root.addView(edges, match())
        setContentView(root)
    }

    /** Shows the gradient theme, or the photo for the current rotation slot (cross-fading when it changes). */
    private fun applyBackground() {
        val theme = prefs.background
        val walls = Wallpapers(this, Wallpapers.sourceFor(theme))
        val photo = if (Wallpapers.isPhoto(theme)) walls.current(prefs.wallMinutes) else null
        val key = if (Wallpapers.isPhoto(theme)) "$theme:${photo?.name}" else theme
        if (key == shownBackground) return
        shownBackground = key
        io.execute {
            if (Wallpapers.isPhoto(theme) && !refreshing) {
                refreshing = true
                walls.refresh()
                refreshing = false
                // First run: nothing was cached yet, so try again now that photos exist.
                if (photo == null && walls.files().isNotEmpty()) main.post { shownBackground = null; applyBackground() }
            }
            val bmp = photo?.let { walls.forHome(it) } ?: Backgrounds.render(if (Wallpapers.isPhoto(theme)) "green" else theme)
            val glass = LiquidGlass.bake(bmp, sw, sh, dockRect, dockRadius).also { it.prepareToDraw() }
            homeDock = glass
            main.post {
                setDock(glass, 1200)
                val old = bgPhoto
                val next = android.graphics.drawable.BitmapDrawable(resources, bmp)
                if (old == null) {
                    bg.setImageDrawable(next)
                    bg.alpha = 0f
                    bg.animate().alpha(1f).setDuration(300).start()
                } else {
                    val fade = android.graphics.drawable.TransitionDrawable(arrayOf(old, next))
                    fade.isCrossFadeEnabled = true
                    bg.setImageDrawable(fade)
                    fade.startTransition(1200)
                }
                bgPhoto = next
            }
        }
    }

    private fun setDock(b: Bitmap, fadeMs: Int) {
        val next = BitmapDrawable(resources, b)
        val old = dockShown
        if (old == null) {
            dock.setImageDrawable(next)
        } else {
            val fade = TransitionDrawable(arrayOf(old, next)).apply { isCrossFadeEnabled = true }
            dock.setImageDrawable(fade)
            fade.startTransition(fadeMs)
        }
        dockShown = next
    }

    // ------------------------------------------------------------------- shelf

    private fun buildShelf(side: Int) {
        shelfRow = ShelfRow(this, sw, sh, side, dockRect.top)
        root.addView(shelfRow, FrameLayout.LayoutParams(-1, -1))
    }

    private fun showShelf(pkg: String) {
        shelfPkg = pkg
        val label = adapter.apps.firstOrNull { it.pkg == pkg }?.label ?: ""
        net.execute {
            val items = Shelf.items(this, pkg, label).take(3)
            main.post {
                if (shelfPkg != pkg) return@post
                if (items.isEmpty()) { setShelfVisible(false); return@post }
                setShelfFocus(false)
                shelfRow.fill(items.first().kicker, items)
                setShelfVisible(true)
                for ((card, url) in shelfRow.cardTargets()) net.execute {
                    val b = Shelf.thumb(this, url, card.w)
                    main.post { if (shelfPkg == pkg) card.setImage(b) }
                }
            }
        }
    }

    private fun setShelfVisible(v: Boolean) {
        if (v == shelfShown) return
        shelfShown = v
        shelfRow.show(v)
    }

    private fun hideShelf() {
        setShelfFocus(false)
        main.removeCallbacks(shelfShow)
        pendingShelf = null
        shelfPkg = null
        setShelfVisible(false)
    }

    private fun setShelfFocus(on: Boolean) {
        if (shelfFocus == on) return
        shelfFocus = on
        shelfRow.setFocused(on)
        // The dock tile steps back while the cards have the focus, and lifts again when it returns.
        (grid.findViewHolderForAdapterPosition(focusedPos)?.itemView as? TileCell)?.setFocusedLook(!on, 0, 0, showLabel = false)
    }

    /** Left/Right while the cards have the focus. */
    private fun stepShelf(dx: Int) {
        shelfRow.move(dx)
    }

    /** OK on a card: resume the episode (or play the video) the app published. */
    private fun playShelf() {
        val item = shelfRow.current() ?: return
        val uri = item.intent ?: return
        val pkg = shelfPkg
        setShelfFocus(false)
        try {
            startActivity(Intent.parseUri(uri, Intent.URI_INTENT_SCHEME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            val i = adapter.apps.firstOrNull { it.pkg == pkg }?.let { repo.launchIntent(it) }
            if (i != null) runCatching { startActivity(i) }
            else Toast.makeText(this, "Impossible d'ouvrir ce contenu", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateClock() {
        val now = Date()
        dateText.text = SimpleDateFormat("EEEE d MMMM", Locale.FRANCE).format(now)
        clockText.text = SimpleDateFormat("HH:mm", Locale.FRANCE).format(now)
    }

    private fun updateNetworkIcon() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (e: SecurityException) { null }
        val wired = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        netButton.setIcon(if (wired) R.drawable.ic_ethernet else R.drawable.ic_wifi)
        netButton.alpha = if (caps == null) 0.4f else 1f
    }

    /** Pins a TV input to the grid, or takes it off. */
    fun pinInput(i: Bravia.Input, on: Boolean) {
        val enc = InputTile.encode(i)
        prefs.pinnedInputs = prefs.pinnedInputs.filter { InputTile.decode(it)?.uri != i.uri }.toSet() + if (on) setOf(enc) else emptySet()
        if (on) prefs.hidden = prefs.hidden - InputTile.key(i)
        reload()
    }

    fun isPinned(i: Bravia.Input) = prefs.pinnedInputs.any { InputTile.decode(it)?.uri == i.uri }

    private fun reload(changed: String? = null) {
        io.execute {
            val list = repo.orderedVisible()
            main.post {
                val focusedPkg = adapter.apps.getOrNull(focusedPos)?.pkg
                adapter.submit(list, changed)
                val idx = list.indexOfFirst { it.pkg == focusedPkg }.coerceAtLeast(0)
                grid.post {
                    // On first show the header buttons grab default focus; the grid should have it.
                    val headerOk = header.hasFocus() && !firstFocus
                    if (!grid.hasFocus() && !headerOk && sheets.isEmpty() && !control.isOpen) focusAt(idx)
                    firstFocus = false
                    // A new list can leave the grid between rows; put the focused row back where it belongs.
                    grid.post { grid.focusedChild?.let { grid.layoutManager?.requestChildRectangleOnScreen(grid, it, Rect(), false, true) } }
                }
            }
        }
    }

    /** How far the grid is scrolled from its resting place, worked out from the first laid-out tile. */
    private fun gridOffset(): Int? {
        val lm = grid.layoutManager ?: return null
        val child = grid.getChildAt(0) ?: return null
        val p = grid.getChildAdapterPosition(child).takeIf { it >= 0 } ?: return null
        return firstRowY + (p / cols) * rowPitch - lm.getDecoratedTop(child)
    }

    private fun focusAt(pos: Int) {
        if (adapter.itemCount == 0) return
        val p = pos.coerceIn(0, adapter.itemCount - 1)
        val vh = grid.findViewHolderForAdapterPosition(p)
        if (vh != null) vh.itemView.requestFocus() else {
            grid.scrollToPosition(p)
            grid.post { grid.findViewHolderForAdapterPosition(p)?.itemView?.requestFocus() }
        }
    }

    // ------------------------------------------------------------------- tiles

    override fun onTileFocused(pos: Int, cell: TileCell) {
        if (shelfFocus) setShelfFocus(false)
        focusedPos = pos
        val pkg = adapter.apps.getOrNull(pos)?.pkg
        if (pkg != null) edges.show(repo.accent(pkg))
        main.removeCallbacks(shelfShow)
        if (pos < cols && pkg != null && Shelf.has(this, pkg) && moving < 0) {
            if (pkg != shelfPkg || !shelfShown) {
                if (shelfShown) hideShelf()
                pendingShelf = pkg
                // Wait for focus to settle so sliding along the dock does not flicker.
                main.postDelayed(shelfShow, 300)
            }
        } else hideShelf()
    }

    override fun onTileClicked(pos: Int, cell: TileCell) {
        if (moving >= 0) { endMove(); return }
        if (shelfFocus) { playShelf(); return }
        val app = adapter.apps.getOrNull(pos) ?: return
        app.input?.let { i -> io.execute { tv.setInput(i) }; return }
        // The stock launcher (Xiaomi's PatchWall) can only be opened through the system Home action.
        if (HomeGuardService.isStockHome(app.pkg) && HomeGuardService.openStockLauncher()) return
        val intent = repo.launchIntent(app) ?: return
        val opts = ActivityOptions.makeScaleUpAnimation(cell.card, 0, 0, cell.card.width, cell.card.height)
        try {
            startActivity(intent, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Impossible d'ouvrir ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onTileLongPressed(pos: Int, cell: TileCell) {
        if (moving >= 0) return
        val app = adapter.apps.getOrNull(pos) ?: return
        app.input?.let { i ->
            showSheet(Sheet(root).title(app.label).message(null)
                .button("Modifier l'accueil") { startMove(pos) }
                .button("Retirer de l'accueil") { pinInput(i, false) }
                .button("Annuler") {}, restoreFocusTo = pos)
            return
        }
        val s = Sheet(root).title(app.label).message(null)
            .button("Modifier l'accueil") { startMove(pos) }
            .button("Masquer") {
                prefs.hidden = prefs.hidden + app.pkg
                reload()
            }
        if (repo.isOwn(app.pkg)) {
            showSheet(s.button("Annuler") {}, restoreFocusTo = pos)
            return
        }
        s.button("Infos sur l'appli") {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        if (!repo.isEssential(app.pkg)) s.button("Supprimer l'appli") { confirmDelete(app, pos) }
        s.button("Annuler") {}
        showSheet(s, restoreFocusTo = pos)
    }

    /**
     * Deletes through local adb, which also removes preinstalled apps the system
     * won't let go of ("pm uninstall --user 0": the APK stays on the system
     * partition, so it can be restored from settings).
     */
    private fun confirmDelete(app: AppEntry, pos: Int) {
        val system = repo.isSystem(app.pkg)
        if (!prefs.adbAuthorized) {
            if (!system) {
                startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            showSheet(Sheet(root).title("Autoriser d'abord le débogage local")
                .message("Supprimer une appli préinstallée nécessite le débogage local. Après « Commencer », la TV demande « Autoriser le débogage ? ». Cochez « Toujours autoriser », puis « Autoriser ».")
                .button("Commencer") { authorizeAdb() }
                .button("Annuler") {}, restoreFocusTo = pos)
            return
        }
        val msg = when {
            system -> "C'est une appli préinstallée. Une fois supprimée, elle n'apparaît plus et ne tourne plus en arrière-plan. Vous pourrez la restaurer dans « Réglages de l'accueil → Applis préinstallées supprimées »."
            repo.isLauncher(app.pkg) -> "C'est un écran d'accueil de secours, utile si celui-ci ne démarre plus. Sans lui, la TV n'aurait plus d'accueil de rechange. L'appli et ses données seront supprimées."
            else -> "L'appli et ses données seront supprimées."
        }
        showSheet(Sheet(root).title("Supprimer « ${app.label} » ?").message(msg)
            .button("Supprimer") {
                io.execute {
                    val out = LocalAdb.shell(this, if (system) "pm uninstall -k --user 0 ${app.pkg}" else "pm uninstall ${app.pkg}", 15_000)
                    val ok = out?.contains("Success") == true
                    main.post {
                        if (ok) {
                            if (system) prefs.removed = prefs.removed + app.pkg
                            reload()
                        } else Toast.makeText(this, "Échec de la suppression : ${out?.trim()?.take(80) ?: "débogage local indisponible"}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .button("Annuler") {}, restoreFocusTo = pos)
    }

    override fun onTileKey(pos: Int, cell: TileCell, keyCode: Int): Boolean {
        val (dx, dy) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
            KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
            else -> return false
        }
        adapter.lastDx = dx
        adapter.lastDy = dy
        val n = adapter.itemCount
        val col = pos % cols
        val target = pos + dx + dy * cols
        val lastRow = (n - 1) / cols
        val atEdge = (dx < 0 && col == 0) || (dx > 0 && (col == cols - 1 || pos == n - 1)) ||
            (dy < 0 && pos < cols) || (dy > 0 && pos / cols == lastRow)

        if (moving >= 0) {
            if (!atEdge) {
                val to = target.coerceIn(0, n - 1)
                adapter.move(pos, to)
                moving = to
                grid.post { focusAt(to) }
            } else Motion.bump(cell.card, dx, dy)
            return true
        }
        if (shelfFocus) {
            when {
                dx != 0 -> stepShelf(dx)
                dy > 0 -> setShelfFocus(false)
                else -> { setShelfFocus(false); ccButton.requestFocus() }
            }
            return true
        }
        if (dy < 0 && pos < cols && shelfShown && shelfItems.any { it.intent != null }) {
            // Up from the dock moves into the shelf, like tvOS's Top Shelf.
            setShelfFocus(true)
            return true
        }
        if (dy < 0 && pos < cols) {
            // Up from the first row reaches the buttons in the top-right corner.
            ccButton.requestFocus()
            return true
        }
        if (atEdge) {
            Motion.bump(cell.card, dx, dy)
            return true
        }
        if (dy > 0 && target >= n) {
            // Partial last row: drop to its last tile like tvOS does.
            focusAt(n - 1)
            return true
        }
        return false
    }

    private fun startMove(pos: Int) {
        moving = pos
        adapter.movingPkg = adapter.apps[pos].pkg
        adapter.notifyItemChanged(pos)
        grid.post { focusAt(pos) }
        Toast.makeText(this, "Déplacez avec les flèches, puis appuyez sur OK", Toast.LENGTH_SHORT).show()
    }

    private fun endMove() {
        val pos = moving
        moving = -1
        adapter.movingPkg = null
        adapter.notifyItemChanged(pos)
        prefs.order = adapter.apps.map { it.pkg }
        grid.post { focusAt(pos) }
    }

    // ------------------------------------------------------------ overlays / keys

    fun showSheet(s: Sheet, restoreFocusTo: Int? = null): Sheet {
        sheets += s
        setGridBlocked(true)
        s.onDismiss {
            sheets -= s
            if (sheets.isEmpty() && control.isOpen) control.refocus()
            else if (sheets.isEmpty()) {
                setGridBlocked(false)
                focusAt(restoreFocusTo ?: focusedPos)
            }
        }
        return s.show()
    }

    /** Keeps D-pad focus from wandering from an overlay onto the screen behind it. */
    fun setGridBlocked(blocked: Boolean) {
        val mode = if (blocked) ViewGroup.FOCUS_BLOCK_DESCENDANTS else ViewGroup.FOCUS_AFTER_DESCENDANTS
        grid.descendantFocusability = mode
        header.descendantFocusability = mode
    }

    fun restoreGridFocus() {
        setGridBlocked(false)
        focusAt(focusedPos)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        restartIdle()
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU)) {
            when {
                sheets.isNotEmpty() -> sheets.last().dismiss()
                control.isOpen -> control.close()
                moving >= 0 -> endMove()
                shelfFocus -> setShelfFocus(false)
                header.hasFocus() -> focusAt(focusedPos)
                else -> control.open()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already home: close everything and go back to the top.
        sheets.toList().forEach { it.dismiss() }
        control.close()
        if (moving >= 0) endMove()
        // The guard brought the screen back (an app exited to the stock launcher): stay where we were.
        if (intent.getBooleanExtra(EXTRA_GUARD, false)) return
        focusedPos = 0
        // Scroll by our own offset: smoothScrollToPosition aligns to the padding and leaves the grid off its resting place.
        grid.smoothScrollBy(0, -scrollY, Motion.ease, 360)
        grid.postDelayed({ focusAt(0) }, 400)
    }

    override fun onStart() {
        super.onStart()
        Guard.ensure(this)
        main.postDelayed({ Guard.rebindIfStalled(this) }, 5_000)
    }

    override fun onResume() {
        super.onResume()
        prefs.homeInFront = true
        HomeGuardService.onHomeResumed()
        restartIdle()
        applyBackground()
        updateNetworkIcon()
        main.removeCallbacks(ticker)
        ticker.run()
        control.refresh()
        // Back from an app where an episode was watched: read the rows again.
        Shelf.invalidate()
        if (shelfShown) shelfPkg?.let { showShelf(it) }
    }

    override fun onPause() {
        prefs.homeInFront = false
        main.removeCallbacks(idleFrame)
        main.removeCallbacks(ticker)
        main.removeCallbacks(shelfAdvance)
        super.onPause()
    }

    private fun askAdbAuthorization() {
        if (prefs.adbAuthorized || isFinishing) return
        showSheet(Sheet(root).title("Activer l'extinction et le nettoyage")
            .message("Il faut autoriser le débogage local une fois. Après « Commencer », la TV demande « Autoriser le débogage ? ». Cochez « Toujours autoriser », puis « Autoriser ».")
            .button("Commencer") { authorizeAdb() }
            .button("Plus tard") {})
    }

    fun authorizeAdb() {
        Toast.makeText(this, "Dans la fenêtre, cochez « Toujours autoriser » puis « Autoriser »", Toast.LENGTH_LONG).show()
        io.execute {
            val ok = LocalAdb.shell(this, "echo ok", 60_000) != null
            main.post {
                Toast.makeText(this, if (ok) "Activé : extinction et nettoyage disponibles" else "Autorisation refusée. Vous pouvez réessayer dans les réglages de l'accueil", Toast.LENGTH_LONG).show()
                control.refresh()
            }
        }
    }
}

/**
 * Darkens the shelf picture where text and the dock sit: along the bottom, to
 * the left behind the title, and a little at the top behind the corner buttons.
 */
class ShelfScrim : Drawable() {
    private val p = Paint()

    override fun draw(c: Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        p.shader = LinearGradient(0f, h * 0.3f, 0f, h, Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, w * 0.62f, 0f, Color.argb(150, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = LinearGradient(0f, 0f, 0f, h * 0.2f, Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/** Round glyph button for the top-right corner; fills white when focused. */
class IconButton(ctx: Context, icon: Int, onClick: () -> Unit) : FrameLayout(ctx) {
    private val img = ImageView(ctx).apply { setImageResource(icon) }
    private val disc = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
    }

    init {
        isFocusable = true
        isClickable = true
        background = disc
        addView(img, LayoutParams(ctx.dp(20), ctx.dp(20), Gravity.CENTER))
        setOnClickListener { onClick() }
        setOnFocusChangeListener { _, f ->
            disc.setColor(if (f) Color.WHITE else Color.TRANSPARENT)
            img.setColorFilter(if (f) Color.BLACK else Color.WHITE)
            animate().scaleX(if (f) 1.1f else 1f).scaleY(if (f) 1.1f else 1f).setDuration(150).start()
        }
    }

    fun setIcon(res: Int) = img.setImageResource(res)
}
