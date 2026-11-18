package com.crate.install

import java.io.File
import org.json.JSONObject

/** One installed VM: the image it came from plus the hardware it is given. */
data class Machine(
    val id: String,
    val imageId: String,
    val name: String,
    val version: String,
    val desktop: String,
    val cmdline: String,
    val cpus: Int,
    val memoryGB: Int,
    val diskGB: Int,
    val installedAt: Long,
) {
    fun dir(context: android.content.Context): File =
        File(Installer.vmsRoot(context), id)

    fun write(f: File) {
        val o = JSONObject()
            .put("id", id)
            .put("imageId", imageId)
            .put("name", name)
            .put("version", version)
            .put("desktop", desktop)
            .put("cmdline", cmdline)
            .put("cpus", cpus)
            .put("memoryGB", memoryGB)
            .put("diskGB", diskGB)
            .put("installedAt", installedAt)
        f.writeText(o.toString())
    }

    companion object {
        fun read(f: File): Machine? = try {
            val o = JSONObject(f.readText())
            Machine(
                id = o.getString("id"),
                imageId = o.getString("imageId"),
                name = o.getString("name"),
                version = o.getString("version"),
                desktop = o.getString("desktop"),
                cmdline = o.getString("cmdline"),
                cpus = o.optInt("cpus", 4),
                memoryGB = o.optInt("memoryGB", 4),
                diskGB = o.optInt("diskGB", 12),
                installedAt = o.optLong("installedAt", 0),
            )
        } catch (_: Exception) {
            null
        }
    }
}
