package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background pictures, cached on disk so the home background and the photo
 * frame can rotate through them. Two sources, each in its own folder:
 * - [BING]: Bing's daily photos (cn.bing.com works without a VPN); the last
 *   ~15 days are fetched once a day.
 * - [ANIME]: the month's most popular anime wallpapers on Wallhaven, SFW
 *   only, a dozen new ones a day.
 * - [MINE]: photos sent from a phone (PhoneServer); never pruned.
 * - [CUSTOM]: links the user added ([Prefs.customWallUrls]). A link may be a
 *   picture, an API that answers with a different picture each time, or a
 *   page / JSON that lists picture addresses.
 * Older pictures are kept up to [MAX_FILES].
 */
class Wallpapers(private val ctx: Context, private val source: String = BING) {
    private val dir = File(ctx.filesDir, when (source) {
        ANIME -> "wall-anime"
        MINE -> "wall-mine"
        CUSTOM -> "wall-custom"
        else -> "wall"
    }).apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")

    /** Cached files, newest first (Bing files are named by date; others by download time). */
    fun files(): List<File> {
        val all = dir.listFiles { f -> f.name.endsWith(".jpg") } ?: return emptyList()
        return if (source == BING) all.sortedByDescending { it.name } else all.sortedByDescending { it.lastModified() }
    }

    /** The photo for the current time slot when rotating every [minutes]. */
    fun current(minutes: Int): File? {
        val all = files()
        if (all.isEmpty()) return null
        val slot = System.currentTimeMillis() / (minutes.coerceAtLeast(1) * 60_000L)
        return all[(slot % all.size).toInt()]
    }

    /** "Title — place (© photographer)" for the photo frame caption. */
    fun caption(f: File): String = try {
        JSONObject(metaFile.readText()).optString(f.name)
    } catch (e: Exception) { "" }

    /** Blocking; fetches new photos if the last refresh is older than ~a day. */
    fun refresh(force: Boolean = false) {
        if (source == MINE) return
        // One download per source at a time: the home screen and the phone page may both ask, and a
        // second run would otherwise fetch a random-picture link all over again.
        synchronized(LOCKS.getOrPut(source) { Any() }) { refreshLocked(force) }
    }

    private fun refreshLocked(force: Boolean) {
        val stamp = File(dir, "stamp")
        val fresh = stamp.exists() && metaFile.exists() && System.currentTimeMillis() - stamp.lastModified() < 20 * 3_600_000L
        // A custom link may well be a single picture, so four files are not required there.
        if (!force && fresh && (files().size >= 4 || source == CUSTOM)) return
        val meta = try { JSONObject(metaFile.readText()) } catch (e: Exception) { JSONObject() }
        try {
            when (source) {
                ANIME -> fetchAnime(meta)
                CUSTOM -> fetchCustom(meta)
                else -> fetchBing(meta)
            }
            files().drop(MAX_FILES).forEach { meta.remove(it.name); it.delete() }
            metaFile.writeText(meta.toString())
            stamp.writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.w("Wallpapers", "refresh failed", e)
            if (meta.length() > 0) metaFile.writeText(meta.toString())
        }
    }

    private fun fetchBing(meta: JSONObject) {
            for (idx in listOf(0, 7)) {
                val json = get("https://www.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=fr-FR").decodeToString()
                val imgs = JSONObject(json).getJSONArray("images")
                for (i in 0 until imgs.length()) {
                    val o = imgs.getJSONObject(i)
                    val name = o.getString("startdate") + ".jpg"
                    val title = o.optString("title")
                    val copy = o.optString("copyright")
                    meta.put(name, if (title.isNotEmpty() && !copy.startsWith(title)) "$title — $copy" else copy)
                    val f = File(dir, name)
                    if (f.exists() && f.length() > 0) continue
                    val tmp = File(dir, "$name.part")
                    tmp.writeBytes(get("https://www.bing.com" + o.getString("urlbase") + "_1920x1080.jpg"))
                    tmp.renameTo(f)
                }
            }
    }

    private fun fetchAnime(meta: JSONObject) {
        // A different page of the month's ranking each day keeps the set changing.
        val page = 1 + (System.currentTimeMillis() / 86_400_000L % 5).toInt()
        val list = JSONObject(get("https://wallhaven.cc/api/v1/search?categories=010&purity=100&sorting=toplist" +
            "&topRange=1M&atleast=1920x1080&ratios=16x9&page=$page").decodeToString()).getJSONArray("data")
        var added = 0
        for (i in 0 until list.length()) {
            if (added >= 12) break
            val o = list.getJSONObject(i)
            // Some originals are 10000 px wide; not worth the download or the memory.
            if (o.optLong("file_size") > 8_000_000) continue
            val name = "wh-" + o.getString("id") + ".jpg"
            val f = File(dir, name)
            if (f.exists() && f.length() > 0) continue
            val bmp = fitScreen(get(o.getString("path"))) ?: continue
            val tmp = File(dir, "$name.part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            tmp.renameTo(f)
            meta.put(name, "Wallhaven · wallhaven.cc/w/" + o.getString("id"))
            added++
        }
    }

    /**
     * Each link is fetched once; when it answers with a picture it is asked again (random-picture
     * APIs give a new one each time) until a repeat or [PER_LINK]. Anything else is searched for
     * picture addresses, JSON included.
     */
    private fun fetchCustom(meta: JSONObject) {
        val links = Prefs(ctx).customWallUrls.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
        for (link in links) {
            val host = runCatching { URL(link).host }.getOrDefault(link)
            val first = runCatching { fetch(link) }.getOrNull() ?: continue
            if (first.second?.startsWith("image/") == true || looksLikeImage(first.first)) {
                var bytes: ByteArray? = first.first
                var n = 0
                while (bytes != null && n < PER_LINK) {
                    if (!save(bytes, meta, host)) break
                    n++
                    bytes = runCatching { fetch(link).first }.getOrNull()
                }
            } else {
                val text = first.first.decodeToString().replace("\\/", "/")
                IMAGE_URL.findAll(text).map { it.value }.distinct().take(PER_PAGE).forEach { u ->
                    runCatching { save(fetch(u).first, meta, host) }
                }
            }
        }
    }

    /** Stores a picture under a name taken from its content; false if it is not one or is already here. */
    private fun save(bytes: ByteArray, meta: JSONObject, caption: String): Boolean {
        val name = "u-" + hash(bytes) + ".jpg"
        if (File(dir, name).exists() || !write(bytes, name)) return false
        meta.put(name, caption)
        return true
    }

    /** A photo sent from a phone, for [MINE]. Returns its file name, or null if it isn't a picture. */
    fun add(bytes: ByteArray): String? {
        val name = "my-" + hash(bytes) + ".jpg"
        return if (File(dir, name).exists() || write(bytes, name)) name else null
    }

    private fun write(bytes: ByteArray, name: String): Boolean {
        val bmp = fitScreen(bytes) ?: return false
        val tmp = File(dir, "$name.part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return tmp.renameTo(File(dir, name))
    }

    fun delete(name: String) {
        if (name.contains('/') || !name.endsWith(".jpg")) return
        File(dir, name).delete()
    }

    private fun hash(b: ByteArray) =
        java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    private fun looksLikeImage(b: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(b, 0, b.size, it) }.outWidth > 0

    /** GET with the content type, following redirects across http and https (HttpURLConnection won't). */
    private fun fetch(url: String): Pair<ByteArray, String?> {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Tigris")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location") ?: error("redirect without Location")).toString()
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("HTTP $code")
            if (c.contentLengthLong > MAX_DOWNLOAD) error("too large")
            val bytes = c.inputStream.use { it.readBytes() }
            return bytes to c.contentType
        }
        error("too many redirects")
    }

    /** Decodes a large picture straight down to 1920x1080. */
    private fun fitScreen(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (b.width == 1920 && b.height == 1080) return b
        return Bitmap.createScaledBitmap(b, 1920, 1080, true).also { if (it !== b) b.recycle() }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Tigris")
        return c.inputStream.use { it.readBytes() }
    }

    fun decode(f: File, full: Boolean = false): Bitmap? = try {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = if (full) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            inMutable = true
        })
    } catch (e: OutOfMemoryError) { null }

    /** A photo dressed for the home screen: darker at the bottom and top-left so tiles and clock stay legible. */
    fun forHome(f: File): Bitmap? = decode(f)?.also { b ->
        val c = Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val p = Paint()
        p.shader = LinearGradient(0f, h * 0.35f, 0f, h, Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = android.graphics.RadialGradient(w * 0.12f, h * 0.38f, w * 0.45f,
            Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    companion object {
        const val MAX_FILES = 40
        const val BING = "daily"
        const val ANIME = "anime"
        const val MINE = "mine"
        const val CUSTOM = "custom"
        val SOURCES = listOf(BING, ANIME, MINE, CUSTOM)
        private val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private const val PER_LINK = 6
        private const val PER_PAGE = 12
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024
        private val IMAGE_URL = Regex("""https?://[^\s"'<>()\\]+?\.(?:jpe?g|png|webp)(?:\?[^\s"'<>()\\]*)?""", RegexOption.IGNORE_CASE)

        fun label(source: String) = when (source) {
            ANIME -> "Illustrations (Wallhaven)"
            MINE -> "Mes photos"
            CUSTOM -> "Liens personnalisés"
            else -> "Paysages (Bing)"
        }

        /** The picture source behind a [Prefs.background] value; gradient themes fall back to Bing. */
        fun sourceFor(background: String) = if (background in SOURCES) background else BING
        fun isPhoto(background: String) = background in SOURCES

        /** Cheap, smooth blur: shrink, box-blur three times, let the view scale it back up. */
        fun blurred(src: Bitmap, darken: Float = 0.45f): Bitmap {
            val w = 96
            val h = (w * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, 4) }
            for (i in px.indices) {
                val c = px[i]
                px[i] = Color.rgb(
                    (Color.red(c) * (1 - darken)).toInt(),
                    (Color.green(c) * (1 - darken)).toInt(),
                    (Color.blue(c) * (1 - darken)).toInt(),
                )
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        /** Dark vertical gradient used before any wallpaper is cached. */
        fun fallback(): Bitmap {
            val b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            Paint().apply {
                shader = LinearGradient(0f, 0f, 32f, 18f, Color.rgb(38, 44, 66), Color.rgb(10, 10, 14), Shader.TileMode.CLAMP)
                Canvas(b).drawRect(0f, 0f, 32f, 18f, this)
            }
            return b
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
            val tmp = IntArray(px.size)
            pass(px, tmp, w, h, r, true)
            pass(tmp, px, w, h, r, false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var rs = 0; var gs = 0; var bs = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = if (horizontal) src[line * w + j] else src[j * w + line]
                        rs += (c shr 16) and 0xff; gs += (c shr 8) and 0xff; bs += c and 0xff; n++
                    }
                    val out = (0xff shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                }
            }
        }
    }
}

/** The soft multi-colour gradients tvOS 26 uses behind the home grid. */
object Backgrounds {
    /** key -> (label, colour blobs as x, y, radius (fractions of width), colour). */
    val THEMES: LinkedHashMap<String, Pair<String, List<Blob>>> = linkedMapOf(
        "green" to ("Vert" to listOf(
            Blob(0.10f, 0.35f, 0.75f, 0xFF0B7A2E.toInt()),
            Blob(0.48f, -0.05f, 0.70f, 0xFF02B43C.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF0F7F8A.toInt()),
            Blob(1.05f, 0.80f, 0.45f, 0xFF3C2B8F.toInt()),
        )),
        "blue" to ("Bleu" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF0B3C8C.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF1E6FE0.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF16A3C4.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF5B2EA6.toInt()),
        )),
        "purple" to ("Violet" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF4A1C8C.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF8E3BE0.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFFD24C9A.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF2D3FA8.toInt()),
        )),
        "orange" to ("Orange" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF9A3412.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFFF0802A.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFFE0466A.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF6B2FA0.toInt()),
        )),
        "graphite" to ("Gris foncé" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF2A2D34.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF4A505C.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF36404F.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF2B2A40.toInt()),
        )),
    )

    data class Blob(val x: Float, val y: Float, val r: Float, val color: Int)

    /** Rendered small; the ImageView's bilinear upscale keeps it perfectly smooth. */
    fun render(key: String): Bitmap {
        val blobs = (THEMES[key] ?: THEMES.getValue("green")).second
        val w = 480
        val h = 270
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(6, 10, 10))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (bl in blobs) {
            p.shader = android.graphics.RadialGradient(bl.x * w, bl.y * w, bl.r * w,
                intArrayOf(bl.color, Color.argb(140, Color.red(bl.color), Color.green(bl.color), Color.blue(bl.color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        // Darken the bottom so the lower rows sit on near-black, as in tvOS.
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.argb(235, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
package com.leslie.tvhome

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Background pictures, cached on disk so the home background and the photo
 * frame can rotate through them. Two sources, each in its own folder:
 * - [BING]: Bing's daily photos (cn.bing.com works without a VPN); the last
 *   ~15 days are fetched once a day.
 * - [ANIME]: the month's most popular anime wallpapers on Wallhaven, SFW
 *   only, a dozen new ones a day.
 * - [MINE]: photos sent from a phone (PhoneServer); never pruned.
 * - [CUSTOM]: links the user added ([Prefs.customWallUrls]). A link may be a
 *   picture, an API that answers with a different picture each time, or a
 *   page / JSON that lists picture addresses.
 * Older pictures are kept up to [MAX_FILES].
 */
class Wallpapers(private val ctx: Context, private val source: String = BING) {
    private val dir = File(ctx.filesDir, when (source) {
        ANIME -> "wall-anime"
        MINE -> "wall-mine"
        CUSTOM -> "wall-custom"
        else -> "wall"
    }).apply { mkdirs() }
    private val metaFile = File(dir, "meta.json")

    /** Cached files, newest first (Bing files are named by date; others by download time). */
    fun files(): List<File> {
        val all = dir.listFiles { f -> f.name.endsWith(".jpg") } ?: return emptyList()
        return if (source == BING) all.sortedByDescending { it.name } else all.sortedByDescending { it.lastModified() }
    }

    /** The photo for the current time slot when rotating every [minutes]. */
    fun current(minutes: Int): File? {
        val all = files()
        if (all.isEmpty()) return null
        val slot = System.currentTimeMillis() / (minutes.coerceAtLeast(1) * 60_000L)
        return all[(slot % all.size).toInt()]
    }

    /** "Title — place (© photographer)" for the photo frame caption. */
    fun caption(f: File): String = try {
        JSONObject(metaFile.readText()).optString(f.name)
    } catch (e: Exception) { "" }

    /** Blocking; fetches new photos if the last refresh is older than ~a day. */
    fun refresh(force: Boolean = false) {
        if (source == MINE) return
        // One download per source at a time: the home screen and the phone page may both ask, and a
        // second run would otherwise fetch a random-picture link all over again.
        synchronized(LOCKS.getOrPut(source) { Any() }) { refreshLocked(force) }
    }

    private fun refreshLocked(force: Boolean) {
        val stamp = File(dir, "stamp")
        val fresh = stamp.exists() && metaFile.exists() && System.currentTimeMillis() - stamp.lastModified() < 20 * 3_600_000L
        // A custom link may well be a single picture, so four files are not required there.
        if (!force && fresh && (files().size >= 4 || source == CUSTOM)) return
        val meta = try { JSONObject(metaFile.readText()) } catch (e: Exception) { JSONObject() }
        try {
            when (source) {
                ANIME -> fetchAnime(meta)
                CUSTOM -> fetchCustom(meta)
                else -> fetchBing(meta)
            }
            files().drop(MAX_FILES).forEach { meta.remove(it.name); it.delete() }
            metaFile.writeText(meta.toString())
            stamp.writeText(System.currentTimeMillis().toString())
        } catch (e: Exception) {
            Log.w("Wallpapers", "refresh failed", e)
            if (meta.length() > 0) metaFile.writeText(meta.toString())
        }
    }

    private fun fetchBing(meta: JSONObject) {
            for (idx in listOf(0, 7)) {
                val json = get("https://cn.bing.com/HPImageArchive.aspx?format=js&idx=$idx&n=8&mkt=zh-CN").decodeToString()
                val imgs = JSONObject(json).getJSONArray("images")
                for (i in 0 until imgs.length()) {
                    val o = imgs.getJSONObject(i)
                    val name = o.getString("startdate") + ".jpg"
                    val title = o.optString("title")
                    val copy = o.optString("copyright")
                    meta.put(name, if (title.isNotEmpty() && !copy.startsWith(title)) "$title — $copy" else copy)
                    val f = File(dir, name)
                    if (f.exists() && f.length() > 0) continue
                    val tmp = File(dir, "$name.part")
                    tmp.writeBytes(get("https://cn.bing.com" + o.getString("urlbase") + "_1920x1080.jpg"))
                    tmp.renameTo(f)
                }
            }
    }

    private fun fetchAnime(meta: JSONObject) {
        // A different page of the month's ranking each day keeps the set changing.
        val page = 1 + (System.currentTimeMillis() / 86_400_000L % 5).toInt()
        val list = JSONObject(get("https://wallhaven.cc/api/v1/search?categories=010&purity=100&sorting=toplist" +
            "&topRange=1M&atleast=1920x1080&ratios=16x9&page=$page").decodeToString()).getJSONArray("data")
        var added = 0
        for (i in 0 until list.length()) {
            if (added >= 12) break
            val o = list.getJSONObject(i)
            // Some originals are 10000 px wide; not worth the download or the memory.
            if (o.optLong("file_size") > 8_000_000) continue
            val name = "wh-" + o.getString("id") + ".jpg"
            val f = File(dir, name)
            if (f.exists() && f.length() > 0) continue
            val bmp = fitScreen(get(o.getString("path"))) ?: continue
            val tmp = File(dir, "$name.part")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            tmp.renameTo(f)
            meta.put(name, "Wallhaven · wallhaven.cc/w/" + o.getString("id"))
            added++
        }
    }

    /**
     * Each link is fetched once; when it answers with a picture it is asked again (random-picture
     * APIs give a new one each time) until a repeat or [PER_LINK]. Anything else is searched for
     * picture addresses, JSON included.
     */
    private fun fetchCustom(meta: JSONObject) {
        val links = Prefs(ctx).customWallUrls.lines().map { it.trim() }.filter { it.startsWith("http://") || it.startsWith("https://") }
        for (link in links) {
            val host = runCatching { URL(link).host }.getOrDefault(link)
            val first = runCatching { fetch(link) }.getOrNull() ?: continue
            if (first.second?.startsWith("image/") == true || looksLikeImage(first.first)) {
                var bytes: ByteArray? = first.first
                var n = 0
                while (bytes != null && n < PER_LINK) {
                    if (!save(bytes, meta, host)) break
                    n++
                    bytes = runCatching { fetch(link).first }.getOrNull()
                }
            } else {
                val text = first.first.decodeToString().replace("\\/", "/")
                IMAGE_URL.findAll(text).map { it.value }.distinct().take(PER_PAGE).forEach { u ->
                    runCatching { save(fetch(u).first, meta, host) }
                }
            }
        }
    }

    /** Stores a picture under a name taken from its content; false if it is not one or is already here. */
    private fun save(bytes: ByteArray, meta: JSONObject, caption: String): Boolean {
        val name = "u-" + hash(bytes) + ".jpg"
        if (File(dir, name).exists() || !write(bytes, name)) return false
        meta.put(name, caption)
        return true
    }

    /** A photo sent from a phone, for [MINE]. Returns its file name, or null if it isn't a picture. */
    fun add(bytes: ByteArray): String? {
        val name = "my-" + hash(bytes) + ".jpg"
        return if (File(dir, name).exists() || write(bytes, name)) name else null
    }

    private fun write(bytes: ByteArray, name: String): Boolean {
        val bmp = fitScreen(bytes) ?: return false
        val tmp = File(dir, "$name.part")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bmp.recycle()
        return tmp.renameTo(File(dir, name))
    }

    fun delete(name: String) {
        if (name.contains('/') || !name.endsWith(".jpg")) return
        File(dir, name).delete()
    }

    private fun hash(b: ByteArray) =
        java.security.MessageDigest.getInstance("MD5").digest(b).joinToString("") { "%02x".format(it) }.take(16)

    private fun looksLikeImage(b: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(b, 0, b.size, it) }.outWidth > 0

    /** GET with the content type, following redirects across http and https (HttpURLConnection won't). */
    private fun fetch(url: String): Pair<ByteArray, String?> {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 8000
            c.readTimeout = 15000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Tigris")
            val code = c.responseCode
            if (code in 300..399) {
                u = URL(URL(u), c.getHeaderField("Location") ?: error("redirect without Location")).toString()
                c.disconnect()
                return@repeat
            }
            if (code !in 200..299) error("HTTP $code")
            if (c.contentLengthLong > MAX_DOWNLOAD) error("too large")
            val bytes = c.inputStream.use { it.readBytes() }
            return bytes to c.contentType
        }
        error("too many redirects")
    }

    /** Decodes a large picture straight down to 1920x1080. */
    private fun fitScreen(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 1920 && bounds.outHeight / (sample * 2) >= 1080) sample *= 2
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        if (b.width == 1920 && b.height == 1080) return b
        return Bitmap.createScaledBitmap(b, 1920, 1080, true).also { if (it !== b) b.recycle() }
    }

    private fun get(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 12) Tigris")
        return c.inputStream.use { it.readBytes() }
    }

    fun decode(f: File, full: Boolean = false): Bitmap? = try {
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inPreferredConfig = if (full) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
            inMutable = true
        })
    } catch (e: OutOfMemoryError) { null }

    /** A photo dressed for the home screen: darker at the bottom and top-left so tiles and clock stay legible. */
    fun forHome(f: File): Bitmap? = decode(f)?.also { b ->
        val c = Canvas(b)
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val p = Paint()
        p.shader = LinearGradient(0f, h * 0.35f, 0f, h, Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
        p.shader = android.graphics.RadialGradient(w * 0.12f, h * 0.38f, w * 0.45f,
            Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, p)
    }

    companion object {
        const val MAX_FILES = 40
        const val BING = "daily"
        const val ANIME = "anime"
        const val MINE = "mine"
        const val CUSTOM = "custom"
        val SOURCES = listOf(BING, ANIME, MINE, CUSTOM)
        private val LOCKS = java.util.concurrent.ConcurrentHashMap<String, Any>()
        private const val PER_LINK = 6
        private const val PER_PAGE = 12
        private const val MAX_DOWNLOAD = 15L * 1024 * 1024
        private val IMAGE_URL = Regex("""https?://[^\s"'<>()\\]+?\.(?:jpe?g|png|webp)(?:\?[^\s"'<>()\\]*)?""", RegexOption.IGNORE_CASE)

        fun label(source: String) = when (source) {
            ANIME -> "二次元壁纸"
            MINE -> "我的照片"
            CUSTOM -> "自定义链接"
            else -> "风景照片"
        }

        /** The picture source behind a [Prefs.background] value; gradient themes fall back to Bing. */
        fun sourceFor(background: String) = if (background in SOURCES) background else BING
        fun isPhoto(background: String) = background in SOURCES

        /** Cheap, smooth blur: shrink, box-blur three times, let the view scale it back up. */
        fun blurred(src: Bitmap, darken: Float = 0.45f): Bitmap {
            val w = 96
            val h = (w * src.height / src.width.toFloat()).toInt().coerceAtLeast(1)
            val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            repeat(3) { boxBlur(px, w, h, 4) }
            for (i in px.indices) {
                val c = px[i]
                px[i] = Color.rgb(
                    (Color.red(c) * (1 - darken)).toInt(),
                    (Color.green(c) * (1 - darken)).toInt(),
                    (Color.blue(c) * (1 - darken)).toInt(),
                )
            }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        /** Dark vertical gradient used before any wallpaper is cached. */
        fun fallback(): Bitmap {
            val b = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            Paint().apply {
                shader = LinearGradient(0f, 0f, 32f, 18f, Color.rgb(38, 44, 66), Color.rgb(10, 10, 14), Shader.TileMode.CLAMP)
                Canvas(b).drawRect(0f, 0f, 32f, 18f, this)
            }
            return b
        }

        private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
            val tmp = IntArray(px.size)
            pass(px, tmp, w, h, r, true)
            pass(tmp, px, w, h, r, false)
        }

        private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val len = if (horizontal) w else h
            val lines = if (horizontal) h else w
            for (line in 0 until lines) {
                for (i in 0 until len) {
                    var rs = 0; var gs = 0; var bs = 0; var n = 0
                    for (k in -r..r) {
                        val j = (i + k).coerceIn(0, len - 1)
                        val c = if (horizontal) src[line * w + j] else src[j * w + line]
                        rs += (c shr 16) and 0xff; gs += (c shr 8) and 0xff; bs += c and 0xff; n++
                    }
                    val out = (0xff shl 24) or ((rs / n) shl 16) or ((gs / n) shl 8) or (bs / n)
                    if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                }
            }
        }
    }
}

/** The soft multi-colour gradients tvOS 26 uses behind the home grid. */
object Backgrounds {
    /** key -> (label, colour blobs as x, y, radius (fractions of width), colour). */
    val THEMES: LinkedHashMap<String, Pair<String, List<Blob>>> = linkedMapOf(
        "green" to ("绿色" to listOf(
            Blob(0.10f, 0.35f, 0.75f, 0xFF0B7A2E.toInt()),
            Blob(0.48f, -0.05f, 0.70f, 0xFF02B43C.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF0F7F8A.toInt()),
            Blob(1.05f, 0.80f, 0.45f, 0xFF3C2B8F.toInt()),
        )),
        "blue" to ("蓝色" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF0B3C8C.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF1E6FE0.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF16A3C4.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF5B2EA6.toInt()),
        )),
        "purple" to ("紫色" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF4A1C8C.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF8E3BE0.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFFD24C9A.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF2D3FA8.toInt()),
        )),
        "orange" to ("橙色" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF9A3412.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFFF0802A.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFFE0466A.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF6B2FA0.toInt()),
        )),
        "graphite" to ("深灰" to listOf(
            Blob(0.10f, 0.30f, 0.75f, 0xFF2A2D34.toInt()),
            Blob(0.50f, -0.05f, 0.70f, 0xFF4A505C.toInt()),
            Blob(1.00f, 0.20f, 0.55f, 0xFF36404F.toInt()),
            Blob(1.05f, 0.85f, 0.45f, 0xFF2B2A40.toInt()),
        )),
    )

    data class Blob(val x: Float, val y: Float, val r: Float, val color: Int)

    /** Rendered small; the ImageView's bilinear upscale keeps it perfectly smooth. */
    fun render(key: String): Bitmap {
        val blobs = (THEMES[key] ?: THEMES.getValue("green")).second
        val w = 480
        val h = 270
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(6, 10, 10))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (bl in blobs) {
            p.shader = android.graphics.RadialGradient(bl.x * w, bl.y * w, bl.r * w,
                intArrayOf(bl.color, Color.argb(140, Color.red(bl.color), Color.green(bl.color), Color.blue(bl.color)), Color.TRANSPARENT),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        }
        // Darken the bottom so the lower rows sit on near-black, as in tvOS.
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(),
            Color.TRANSPARENT, Color.argb(235, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        return b
    }
}
