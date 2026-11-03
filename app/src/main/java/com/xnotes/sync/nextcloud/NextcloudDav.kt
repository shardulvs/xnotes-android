package com.xnotes.sync.nextcloud

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** WebDAV and OCS calls against one Nextcloud account. Paths ("hrefs") are server-absolute and percent-encoded. */
class NextcloudDav(private val server: String, user: String, appPassword: String) {
    data class Entry(val href: String, val isFolder: Boolean, val size: Long, val lastModified: Long, val etag: String)

    private val origin = URL(server).let { "${it.protocol}://${it.authority}" }
    private val auth = Credentials.basic(user, appPassword)

    fun rootHref(userId: String) = URL(server).path.trimEnd('/') + "/remote.php/dav/files/" + encode(userId) + "/"

    /** The account's user id, which names its WebDAV root and may differ from the login name. */
    fun userId(): String = call(
        Request.Builder().url("$server/ocs/v2.php/cloud/user?format=json").header("Authorization", auth).header("OCS-APIRequest", "true"),
    ).use { JSONObject(it.body!!.string()).getJSONObject("ocs").getJSONObject("data").getString("id") }

    fun propfind(href: String, depth: Int): List<Entry> =
        call(request(href).header("Depth", "$depth").method("PROPFIND", PROPS.toRequestBody(XML))).use {
            parse(android.util.Xml.newPullParser(), it.body!!.byteStream())
        }

    fun mkcol(href: String) = call(request(href).method("MKCOL", null)).close()

    /** Nextcloud keeps the modification time we send, in whole seconds. */
    fun put(href: String, file: File, lastModified: Long) =
        call(request(href).header("X-OC-Mtime", "${lastModified / 1000}").put(file.asRequestBody(OCTET))).close()

    fun get(href: String, to: File) {
        call(request(href)).use { r -> to.outputStream().use { r.body!!.byteStream().copyTo(it) } }
    }

    /** With [etag], the server refuses (412) when the file changed since it was listed. Nextcloud moves it to its trash bin. */
    fun delete(href: String, etag: String?) =
        call(request(href).apply { if (etag != null) header("If-Match", etag) }.delete()).close()

    private fun request(href: String) = Request.Builder().url(origin + href).header("Authorization", auth)

    private fun call(b: Request.Builder): Response {
        val r = http.newCall(b.build()).execute()
        if (r.isSuccessful) return r
        r.close()
        throw IOException(if (r.code == 401) "Wrong user name or app password" else "Nextcloud answered HTTP ${r.code}")
    }

    companion object {
        private val http = OkHttpClient.Builder().readTimeout(2, TimeUnit.MINUTES).writeTimeout(2, TimeUnit.MINUTES).build()
        private val XML = "application/xml; charset=utf-8".toMediaType()
        private val OCTET = "application/octet-stream".toMediaType()
        private const val DAV = "DAV:"
        private const val PROPS = """<?xml version="1.0"?><d:propfind xmlns:d="DAV:"><d:prop>""" +
            "<d:resourcetype/><d:getcontentlength/><d:getlastmodified/><d:getetag/></d:prop></d:propfind>"

        fun encode(name: String): String = URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        fun decode(href: String): String = URLDecoder.decode(href.replace("+", "%2B"), "UTF-8")

        /** Reads a PROPFIND multistatus. Empty values (the 404 propstat for props a folder lacks) are ignored. */
        fun parse(p: XmlPullParser, input: InputStream): List<Entry> {
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            p.setInput(input, null)
            val out = ArrayList<Entry>()
            var href = ""; var folder = false; var size = 0L; var modified = 0L; var etag = ""
            val text = StringBuilder()
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> {
                        text.setLength(0)
                        if (p.namespace == DAV && p.name == "response") { href = ""; folder = false; size = 0; modified = 0; etag = "" }
                        if (p.namespace == DAV && p.name == "collection") folder = true
                    }
                    XmlPullParser.TEXT -> text.append(p.text)
                    XmlPullParser.END_TAG -> {
                        val v = text.toString().trim()
                        text.setLength(0)
                        if (p.namespace != DAV) continue
                        when (p.name) {
                            "response" -> if (href.isNotEmpty()) out.add(Entry(href, folder, size, modified, etag))
                            else -> if (v.isNotEmpty()) when (p.name) {
                                "href" -> href = v
                                "getcontentlength" -> size = v.toLongOrNull() ?: 0
                                "getlastmodified" -> modified = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                                "getetag" -> etag = v
                            }
                        }
                    }
                }
            }
            return out
        }
    }
}
