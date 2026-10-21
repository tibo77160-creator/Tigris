package com.leslie.tvhome

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.tv.TvContract
import android.net.Uri
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Top Shelf content: what the focused dock app itself publishes on Android TV.
 *
 * 1. Its entries in the system "Watch Next" list (the series and films in progress, with
 *    the resume link and the position): shown first, as "Reprendre".
 * 2. Its own home-screen channels (an app's "Continue Watching" row first, then the others).
 * 3. For YouTube, which publishes neither here, the latest videos of the household's channels.
 *
 * Reading other apps' rows needs READ_TV_LISTINGS (asked once on screen, or granted over adb).
 * Nothing is shown for an app that publishes nothing: the home picture stays.
 */
object Shelf {
    data class Item(
        val kicker: String,
        val title: String,
        val meta: String,
        val image: String,
        /** What OK starts: a resume link (intent URI), or null for a plain picture. */
        val intent: String? = null,
        /** Watched share in percent, or -1 when unknown. */
        val progress: Int = -1,
    )

    const val YOUTUBE = "com.google.android.youtube.tv"
    /** Squeezie, Squeezie Gaming, Mastu, Joyca, Djilsi, Slyders, XQN. */
    private val YOUTUBE_CHANNELS = listOf(
        "UCWeg2Pkate69NFdBeuRFTAw", "UCY-_QmcW09PHAImgVnKxU2g", "UCAhaFPP6v3WCfK5Tjao0B7A",
        "UCow2IGnug1l3Xazkrc5jM_Q", "UCEUZRSYr1a2hnBfnpWslFeQ", "UCBYo-98hFPesnAYFNza9u5g",
        "UCc25vV0S7nAZ-9MxRLyOlkQ",
    )

    private const val UA = "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    /** Progress changes as soon as an episode is watched, so the TV rows are re-read often. */
    private const val TV_TTL = 60_000L
    private const val YT_TTL = 30 * 60_000L
    private const val MAX_ITEMS = 8
    private val cache = HashMap<String, Pair<Long, List<Item>>>()

    /** Every real app can have a shelf; it only shows if the app actually publishes something. */
    fun has(ctx: Context, pkg: String) = pkg != ctx.packageName && !InputTile.isInput(pkg)

    /** Forgets the cached rows, e.g. when coming back from an app where an episode was watched. */
    fun invalidate() = synchronized(cache) { cache.clear() }

    /** Items for [pkg] (whose name is [label]); empty when it publishes nothing. Call off the main thread. */
    fun items(ctx: Context, pkg: String, label: String): List<Item> {
        val ttl = if (pkg == YOUTUBE) YT_TTL else TV_TTL
        synchronized(cache) {
            cache[pkg]?.let { (at, list) -> if (System.currentTimeMillis() - at < ttl) return list }
        }
        val list = try {
            val tv = fromTv(ctx, pkg, label)
            // YouTube: a video in progress first (if YouTube shares it), then the channels' latest videos.
            if (pkg == YOUTUBE) (tv.filter { it.progress >= 0 } + youtube()).take(MAX_ITEMS) else tv
        } catch (e: Exception) {
            Log.w("Shelf", "$pkg failed", e)
            emptyList()
        }
        synchronized(cache) { cache[pkg] = System.currentTimeMillis() to list }
        return list
    }

    // ------------------------------------------------------------- Android TV rows

    private fun fromTv(ctx: Context, pkg: String, label: String): List<Item> {
        val out = ArrayList<Item>()
        // 1. Watch Next, most recently watched first.
        val next = ArrayList<Pair<Long, Item>>()
        query(ctx, TvContract.WatchNextPrograms.CONTENT_URI) { r ->
            if (r.str("package_name") != pkg) return@query
            val item = program(r, "Reprendre sur $label") ?: return@query
            next += r.num("last_engagement_time_utc_millis") to item
        }
        out += next.sortedByDescending { it.first }.map { it.second }
        // 2. The app's own channels: "Continue Watching" first, then the rest in the app's order.
        val channels = ArrayList<Pair<Long, String>>()
        query(ctx, TvContract.Channels.CONTENT_URI) { r ->
            if (r.str("package_name") != pkg || r.str("browsable") == "0") return@query
            channels += r.num("_id") to (r.str("display_name") ?: label)
        }
        val ordered = channels.sortedBy { (_, name) -> if (isContinue(name)) 0 else 1 }
        for ((id, name) in ordered) {
            if (out.size >= MAX_ITEMS) break
            val kicker = if (isContinue(name)) "Reprendre sur $label" else "$label · $name"
            query(ctx, TvContract.buildPreviewProgramsUriForChannel(id)) { r ->
                if (out.size >= MAX_ITEMS) return@query
                program(r, kicker)?.let { p -> if (out.none { it.title == p.title }) out += p }
            }
        }
        return out.take(MAX_ITEMS)
    }

    private fun isContinue(name: String) = name.contains("continue", true) || name.contains("reprendre", true) ||
        name.contains("en cours", true) || name.contains("watching", true)

    private class Row(val c: android.database.Cursor) {
        fun str(col: String): String? = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) null else c.getString(it) }
        fun num(col: String): Long = c.getColumnIndex(col).let { if (it < 0 || c.isNull(it)) 0L else c.getLong(it) }
    }

    private fun query(ctx: Context, uri: Uri, each: (Row) -> Unit) {
        try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val r = Row(c)
                while (c.moveToNext()) each(r)
            }
        } catch (e: SecurityException) {
            Log.w("Shelf", "no permission to read $uri")
        }
    }

    private fun program(r: Row, kicker: String): Item? {
        if (r.str("browsable") == "0") return null
        val title = r.str("title") ?: return null
        val image = r.str("thumbnail_uri") ?: r.str("poster_art_uri") ?: return null
        val season = r.str("season_display_number")
        val episode = r.str("episode_display_number")
        val episodeTitle = r.str("episode_title")
        val duration = r.num("duration_millis")
        val position = r.num("last_playback_position_millis")
        val started = duration > 0 && position in 1000 until duration
        val meta = listOfNotNull(
            if (season != null && episode != null) "Saison $season · Épisode $episode" else episode?.let { "Épisode $it" },
            episodeTitle?.takeIf { it != title },
            if (started) left(duration - position) else null,
        ).joinToString(" · ")
        return Item(
            kicker = kicker,
            title = title,
            meta = meta.ifEmpty { r.str("short_description")?.take(90) ?: "" },
            image = image,
            intent = r.str("intent_uri"),
            progress = if (started) (position * 100 / duration).toInt() else -1,
        )
    }

    private fun left(ms: Long): String {
        val min = (ms / 60_000).toInt().coerceAtLeast(1)
        return if (min >= 60) "${min / 60} h ${"%02d".format(min % 60)} restantes" else "$min min restantes"
    }

    // ----------------------------------------------------------------------- YouTube

    /** The newest long videos (not Shorts) of [YOUTUBE_CHANNELS], from YouTube's public feeds. */
    private fun youtube(): List<Item> {
        val videos = ArrayList<Pair<String, Item>>()
        for (id in YOUTUBE_CHANNELS) {
            val body = try { get("https://www.youtube.com/feeds/videos.xml?channel_id=$id").decodeToString() } catch (e: Exception) { continue }
            var kept = 0
            for (entry in body.split("<entry>").drop(1)) {
                if (kept >= 3) break
                if ("/shorts/" in entry) continue
                val vid = between(entry, "<yt:videoId>", "</yt:videoId>")
                val title = unescape(between(entry, "<title>", "</title>"))
                val published = between(entry, "<published>", "</published>")
                val author = unescape(between(between(entry, "<author>", "</author>"), "<name>", "</name>"))
                if (vid.isEmpty() || title.isEmpty()) continue
                val watch = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$vid")).setPackage(YOUTUBE)
                videos += published to Item(
                    kicker = "YouTube · $author",
                    title = title,
                    meta = ago(published),
                    image = "https://i.ytimg.com/vi/$vid/maxresdefault.jpg",
                    intent = watch.toUri(Intent.URI_INTENT_SCHEME),
                )
                kept++
            }
        }
        return videos.sortedByDescending { it.first }.map { it.second }.take(MAX_ITEMS)
    }

    private fun between(s: String, a: String, b: String): String {
        val i = s.indexOf(a).takeIf { it >= 0 } ?: return ""
        val j = s.indexOf(b, i + a.length).takeIf { it >= 0 } ?: return ""
        return s.substring(i + a.length, j).trim()
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")

    private fun ago(iso: String): String = try {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val mins = ((System.currentTimeMillis() - f.parse(iso)!!.time) / 60_000).coerceAtLeast(0)
        when {
            mins < 60 -> "Il y a $mins min"
            mins < 24 * 60 -> "Il y a ${mins / 60} h"
            mins < 48 * 60 -> "Hier"
            mins < 30 * 24 * 60 -> "Il y a ${mins / (24 * 60)} jours"
            else -> "Il y a ${mins / (30 * 24 * 60)} mois"
        }
    } catch (e: Exception) { "Nouvelle vidéo" }

    // ----------------------------------------------------------------------- pictures

    /** Downloads (or reads from the disk cache) an item's picture: a web address or a content:// image. */
    fun image(ctx: Context, url: String): Bitmap? {
        val dir = File(ctx.cacheDir, "shelf").apply { mkdirs() }
        val f = File(dir, Integer.toHexString(url.hashCode()) + ".jpg")
        if (!f.exists()) try {
            val bytes = when {
                url.startsWith("http") -> fetchPicture(url)
                else -> ctx.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
            } ?: return null
            val tmp = File(dir, f.name + ".part")
            tmp.writeBytes(bytes)
            tmp.renameTo(f)
            // Keep the cache small: the newest 60 pictures.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.w("Shelf", "image $url failed", e)
            return null
        }
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
            ?.let(::trimBars)
            ?.let(::darken)
            // Start the GPU upload now, off the frame that first shows it.
            ?.also { it.prepareToDraw() }
    }

    /** Not every YouTube video has a full-HD thumbnail: fall back to the 720p one, then the standard one. */
    private fun fetchPicture(url: String): ByteArray {
        if ("/maxresdefault.jpg" !in url) return get(url)
        for (size in listOf("maxresdefault", "hq720", "hqdefault")) {
            try { return get(url.replace("maxresdefault", size)) } catch (e: Exception) { }
        }
        throw java.io.IOException("no thumbnail")
    }

    /**
     * Bakes the scrim that keeps the title readable into the picture itself:
     * a full-screen gradient layer on top would cost the TV's GPU three more
     * full-screen passes on every frame of a cross-fade.
     */
    private fun darken(b: Bitmap): Bitmap {
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
        ShelfScrim().apply { setBounds(0, 0, out.width, out.height) }.draw(android.graphics.Canvas(out))
        return out
    }

    /** Some covers are letterboxed; cut the black bars so the picture fills the screen. */
    private fun trimBars(b: Bitmap): Bitmap {
        fun dark(y: Int): Boolean {
            var max = 0
            for (k in 0 until 32) {
                val c = b.getPixel(k * (b.width - 1) / 31, y)
                max = maxOf(max, (c shr 16) and 255, (c shr 8) and 255, c and 255)
            }
            return max < 28
        }
        var top = 0
        while (top < b.height / 4 && dark(top)) top++
        var bottom = b.height
        while (bottom > b.height * 3 / 4 && dark(bottom - 1)) bottom--
        if (top < 4 && b.height - bottom < 4) return b
        return Bitmap.createBitmap(b, 0, top, b.width, bottom - top)
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 8000
        c.setRequestProperty("User-Agent", UA)
        try {
            if (c.responseCode != 200) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
