package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The Top Shelf as cards above the dock, the way Netflix and the Apple TV app show "Up Next":
 * a heading ("Reprendre sur Netflix") and three 16:9 cards side by side, each with its
 * title, episode and progress bar.
 * The home picture stays behind it. Up from the dock focuses the cards; see HomeActivity.
 */
class ShelfRow(ctx: Context, sw: Int, sh: Int, private val side: Int, dockTop: Int) : FrameLayout(ctx) {
    private val heading = ctx.text(26f, weight = 700).apply {
        setShadowLayer(ctx.dp(10).toFloat(), 0f, ctx.dp(1).toFloat(), Color.argb(150, 0, 0, 0))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false
    }
    private val cards = ArrayList<Card>()
    private val gap = ctx.dp(18)
    private val imgH: Int
    private val cardW: Int

    var items: List<Shelf.Item> = emptyList()
        private set
    var selected = 0
        private set
    var focused = false
        private set

    init {
        clipChildren = false
        clipToPadding = false
        alpha = 0f
        visibility = View.INVISIBLE
        val titleTop = (sh * 0.165f).toInt()
        val cardsTop = titleTop + ctx.dp(50)
        val labels = ctx.dp(50)
        val bottom = dockTop - ctx.dp(24)
        val avail = sw - side * 2
        imgH = minOf(bottom - cardsTop - labels, ((avail * 0.82f - gap * 2) * 9 / 48f).toInt())
        cardW = imgH * 16 / 9
        addView(heading, LayoutParams(avail, LayoutParams.WRAP_CONTENT).apply { leftMargin = side; topMargin = titleTop })
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, imgH + labels + ctx.dp(20)).apply {
            leftMargin = side; topMargin = cardsTop
        })
    }

    /** Shows up to three [list] items under [title]; pictures arrive later through [Card.setImage]. */
    fun fill(title: String, list: List<Shelf.Item>) {
        heading.text = title
        items = list.take(3)
        selected = 0
        row.removeAllViews()
        cards.clear()
        items.forEachIndexed { i, item ->
            val card = Card(context, cardW, imgH, item)
            cards += card
            row.addView(card, LinearLayout.LayoutParams(card.w, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) marginStart = gap
            })
        }
        refreshLook()
    }

    /** Each card with the wide thumbnail it should show. */
    fun cardTargets(): List<Pair<Card, String>> = cards.map { c -> c to c.item.image }

    fun current(): Shelf.Item? = items.getOrNull(selected)

    fun setFocused(on: Boolean) {
        focused = on
        if (!on) selected = 0
        refreshLook()
    }

    /** Moves the selection; false at either end (the caller bumps). */
    fun move(dx: Int): Boolean {
        val n = selected + dx
        if (n !in items.indices) return false
        selected = n
        refreshLook()
        return true
    }

    fun show(v: Boolean) {
        animate().cancel()
        if (v) visibility = View.VISIBLE
        animate().alpha(if (v) 1f else 0f).setDuration(if (v) 380 else 220)
            .withEndAction { if (alpha == 0f) visibility = View.INVISIBLE }.start()
    }

    private fun refreshLook() {
        cards.forEachIndexed { i, c -> c.look(selected = focused && i == selected, dim = focused && i != selected) }
    }

    class Card(ctx: Context, val w: Int, h: Int, val item: Shelf.Item) : LinearLayout(ctx) {
        private val radius = ctx.dp(14).toFloat()
        private val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.argb(50, 255, 255, 255))
        }
        private val ring = View(ctx).apply {
            background = GradientDrawable().apply {
                setStroke(ctx.dp(3), Color.WHITE)
                cornerRadius = radius
            }
            alpha = 0f
        }

        init {
            orientation = VERTICAL
            clipChildren = false
            clipToPadding = false
            val frame = FrameLayout(ctx)
            frame.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            if (item.progress >= 0) {
                val margin = ctx.dp(12)
                val track = FrameLayout(ctx).apply { background = rounded(Color.argb(110, 255, 255, 255), ctx.dp(2).toFloat()) }
                val fill = View(ctx).apply { background = rounded(Color.WHITE, ctx.dp(2).toFloat()) }
                track.addView(fill, FrameLayout.LayoutParams((w - margin * 2) * item.progress.coerceIn(0, 100) / 100, FrameLayout.LayoutParams.MATCH_PARENT))
                frame.addView(track, FrameLayout.LayoutParams(w - margin * 2, ctx.dp(4), android.view.Gravity.BOTTOM).apply {
                    leftMargin = margin; bottomMargin = margin
                })
            }
            frame.clipRounded(radius)
            val holder = FrameLayout(ctx).apply { clipChildren = false; clipToPadding = false }
            holder.addView(frame, FrameLayout.LayoutParams(w, h))
            holder.addView(ring, FrameLayout.LayoutParams(w, h))
            addView(holder, LayoutParams(w, h))
            val title = ctx.text(17f, weight = 600).apply {
                text = item.title
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(ctx.dp(6).toFloat(), 0f, 0f, Color.argb(140, 0, 0, 0))
            }
            val meta = ctx.text(13f, Color.argb(185, 255, 255, 255), weight = 500).apply {
                text = item.meta
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setShadowLayer(ctx.dp(6).toFloat(), 0f, 0f, Color.argb(140, 0, 0, 0))
            }
            addView(title, LayoutParams(w, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(10) })
            addView(meta, LayoutParams(w, LayoutParams.WRAP_CONTENT).apply { topMargin = ctx.dp(2) })
            pivotX = w / 2f
            pivotY = h / 2f
        }

        fun setImage(b: Bitmap?) {
            if (b == null) return
            img.setImageBitmap(b)
            img.alpha = 0f
            img.animate().alpha(1f).setDuration(260).start()
        }

        fun look(selected: Boolean, dim: Boolean) {
            val s = if (selected) 1.06f else 1f
            animate().scaleX(s).scaleY(s).alpha(if (dim) 0.6f else 1f).setDuration(220).setInterpolator(Motion.ease).start()
            ring.animate().alpha(if (selected) 1f else 0f).setDuration(180).start()
            translationZ = if (selected) dp(6).toFloat() else 0f
        }
    }
}
