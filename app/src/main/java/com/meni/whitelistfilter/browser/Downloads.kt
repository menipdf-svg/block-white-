package com.meni.whitelistfilter.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Process
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import com.meni.whitelistfilter.FilterState
import com.meni.whitelistfilter.Rules
import com.meni.whitelistfilter.image.ImageFilterSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class DownloadStatus(val title: String) {
    RUNNING("מוריד"),
    DONE("הושלם"),
    FAILED("נכשל"),
    BLOCKED("נחסם")
}

data class DownloadItem(
    val id: Long,
    val name: String,
    val url: String,
    val mime: String,
    val total: Long,
    val done: Long,
    val status: DownloadStatus,
    val uri: Uri? = null,
    val note: String? = null
)

/**
 * ההורדות נעשות בתוך האפליקציה עצמה (ולא דרך מנהל ההורדות של המערכת),
 * כדי שאותם חוקים של הדפדפן יחולו עליהן. הקבצים נשמרים בתיקיית ההורדות.
 */
object Downloads {
    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items

    private val ids = AtomicLong(1)
    private val pool = Executors.newFixedThreadPool(2)

    fun start(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?
    ) {
        val ctx = context.applicationContext
        val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val mime = mimeType?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val id = ids.getAndIncrement()

        fun set(block: (DownloadItem) -> DownloadItem) =
            _items.update { list -> list.map { if (it.id == id) block(it) else it } }

        _items.update { listOf(DownloadItem(id, name, url, mime, -1, 0, DownloadStatus.RUNNING)) + it }

        // תמונות וסרטונים שנשמרים לגלריה לא עוברים את סינון התמונות — חוסמים אותם כשהסינון פעיל
        val cfg = ImageFilterSettings.config.value
        if (cfg.enabled && cfg.categories.isNotEmpty() && (mime.startsWith("image/") || mime.startsWith("video/"))) {
            set { it.copy(status = DownloadStatus.BLOCKED, note = "הורדת תמונות וסרטונים חסומה כשסינון התמונות פעיל") }
            return
        }

        val host = Uri.parse(url).host
        if (host == null || !Rules.isAllowed(Process.myUid(), host)) {
            host?.let { FilterState.block(Process.myUid(), Rules.normalize(it), true) }
            set { it.copy(status = DownloadStatus.BLOCKED, note = "האתר לא ברשימה הלבנה") }
            return
        }

        pool.execute {
            var target: Uri? = null
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                userAgent?.let { conn.setRequestProperty("User-Agent", it) }
                CookieManager.getInstance().getCookie(url)?.let { conn.setRequestProperty("Cookie", it) }

                val code = conn.responseCode
                if (code !in 200..299) throw Exception("HTTP $code")
                val finalHost = conn.url.host
                if (finalHost != null && !Rules.isAllowed(Process.myUid(), finalHost)) {
                    conn.disconnect()
                    set { it.copy(status = DownloadStatus.BLOCKED, note = "הופנה לאתר שלא ברשימה") }
                    return@execute
                }
                val total = conn.contentLengthLong
                set { it.copy(total = total) }

                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("לא ניתן ליצור קובץ")

                conn.inputStream.use { input ->
                    resolver.openOutputStream(target!!)!!.use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var lastReport = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastReport > 300) {
                                lastReport = now
                                val d = done
                                set { it.copy(done = d) }
                            }
                        }
                        val d = done
                        set { it.copy(done = d) }
                    }
                }
                conn.disconnect()

                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(target!!, values, null, null)
                val uri = target
                set { it.copy(status = DownloadStatus.DONE, uri = uri) }
            } catch (e: Exception) {
                target?.let { try { ctx.contentResolver.delete(it, null, null) } catch (_: Exception) {} }
                set { it.copy(status = DownloadStatus.FAILED, note = e.message) }
            }
        }
    }

    fun remove(id: Long) = _items.update { list -> list.filterNot { it.id == id } }
}
