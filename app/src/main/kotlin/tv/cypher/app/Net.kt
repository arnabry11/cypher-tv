package tv.cypher.app

import tv.cypher.core.Urls
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    class Downloaded(val notModified: Boolean, val etag: String?)

    private const val AGENT = "CypherTV"

    /** Downloads [url] to [dest] (via a temp file). Sends If-None-Match when [etag] is given and [dest] exists. */
    fun download(
        url: String,
        dest: File,
        etag: String? = null,
        onProgress: ((done: Long, total: Long) -> Unit)? = null,
    ): Downloaded {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", AGENT)
            if (etag != null && dest.exists()) conn.setRequestProperty("If-None-Match", etag)
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) return Downloaded(true, etag)
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code for $url")
            val total = conn.contentLengthLong
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress?.invoke(done, total)
                    }
                }
            }
            if (!tmp.renameTo(dest)) { dest.delete(); if (!tmp.renameTo(dest)) throw IOException("Cannot save ${dest.name}") }
            return Downloaded(false, conn.getHeaderField("ETag"))
        } finally {
            conn.disconnect()
        }
    }

    /** Tries [url], then its jsDelivr mirror (without conditional headers) if GitHub is unreachable. */
    fun downloadWithFallback(url: String, dest: File, etag: String? = null): Downloaded =
        try {
            download(url, dest, etag)
        } catch (e: IOException) {
            val mirror = Urls.jsDelivrMirror(url) ?: throw e
            download(mirror, dest, null)
        }
}
