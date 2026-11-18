package com.crate.install

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/** A file in the release, checked against its SHA-256. */
data class CatalogFile(
    val url: String,
    val size: Long,
    val sha256: String,
)

/** The raw disk, compressed and cut into parts. */
data class CatalogDisk(
    val size: Long,
    val sha256: String,
    val compression: String,
    val parts: List<CatalogFile>,
)

/** A system image crate can install: one entry of the catalog. */
data class CatalogImage(
    val id: String,
    val name: String,
    val version: String,
    val desktop: String,
    val cmdline: String,
    val kernel: CatalogFile,
    val initrd: CatalogFile,
    val disk: CatalogDisk,
    val vendorData: String?,
) {
    val downloadBytes: Long
        get() = kernel.size + initrd.size + disk.parts.sumOf { it.size }
}

private fun JSONObject.file(key: String): CatalogFile {
    val o = getJSONObject(key)
    return CatalogFile(o.getString("url"), o.getLong("size"), o.getString("sha256"))
}

/** Fetches + parses the catalog (latest release). */
fun fetchCatalog(): List<CatalogImage> {
    val url = URL("https://github.com/mojila/arm64-desktop-images/releases/latest/download/catalog.json")
    val conn = url.openConnection() as HttpURLConnection
    conn.connectTimeout = 30_000
    conn.readTimeout = 30_000
    try {
        if (conn.responseCode != 200) throw RuntimeException("catalog unavailable (HTTP ${conn.responseCode})")
        val text = conn.inputStream.bufferedReader().readText()
        val root = JSONObject(text)
        val out = mutableListOf<CatalogImage>()
        val images = root.getJSONArray("images")
        for (i in 0 until images.length()) {
            val o = images.getJSONObject(i)
            val d = o.getJSONObject("disk")
            val parts = mutableListOf<CatalogFile>()
            val arr = d.getJSONArray("parts")
            for (j in 0 until arr.length()) {
                val p = arr.getJSONObject(j)
                parts += CatalogFile(p.getString("url"), p.getLong("size"), p.getString("sha256"))
            }
            out += CatalogImage(
                id = o.getString("id"),
                name = o.getString("name"),
                version = o.getString("version"),
                desktop = o.getString("desktop"),
                cmdline = o.getString("cmdline"),
                kernel = o.file("kernel"),
                initrd = o.file("initrd"),
                disk = CatalogDisk(d.getLong("size"), d.getString("sha256"),
                    d.getString("compression"), parts),
                vendorData = if (o.isNull("vendorData")) null else o.getString("vendorData"),
            )
        }
        return out
    } finally {
        conn.disconnect()
    }
}
