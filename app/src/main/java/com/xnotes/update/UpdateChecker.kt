package com.xnotes.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks the n-notes GitHub repo for a newer release. Uses HttpURLConnection + org.json to
 * match the rest of the app (no networking library is pulled in for one feature). The release
 * tag is `v<versionName>` (e.g. v0.8.16-0.15), so the tag minus its "v" compares directly
 * against the installed versionName.
 */
object UpdateChecker {
    private const val LATEST_RELEASE_API = "https://api.github.com/repos/NeDDy3z/n-notes/releases/latest"
    const val RELEASES_URL = "https://github.com/NeDDy3z/n-notes/releases/latest"

    sealed interface Status {
        data class UpToDate(val version: String) : Status
        data class Available(val version: String, val releaseUrl: String, val apkUrl: String?) : Status
    }

    suspend fun check(currentVersionName: String): Result<Status> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(LATEST_RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 15_000
                readTimeout = 15_000
            }
            try {
                if (conn.responseCode !in 200..299) error("GitHub returned HTTP ${conn.responseCode}")
                val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                val tag = json.optString("tag_name").ifEmpty { error("No release found") }
                val latest = tag.removePrefix("v")
                val releaseUrl = json.optString("html_url").ifEmpty { RELEASES_URL }
                val apkUrl = json.optJSONArray("assets")?.let { arr ->
                    (0 until arr.length()).asSequence()
                        .map { arr.getJSONObject(it) }
                        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                        ?.optString("browser_download_url")?.ifEmpty { null }
                }
                if (isNewer(parseVersion(latest), parseVersion(currentVersionName)))
                    Status.Available(latest, releaseUrl, apkUrl)
                else
                    Status.UpToDate(currentVersionName)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** "0.8.16-0.15" -> [0, 8, 16, 0, 15]; any non-numeric suffix (e.g. "-debug") is dropped. */
    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").split('.', '-').mapNotNull { it.toIntOrNull() }

    private fun isNewer(latest: List<Int>, current: List<Int>): Boolean {
        for (i in 0 until maxOf(latest.size, current.size)) {
            val a = latest.getOrElse(i) { 0 }
            val b = current.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
