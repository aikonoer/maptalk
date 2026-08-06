package app.maptalk.data

import android.content.Context
import java.io.File
import java.util.UUID

/** Files for local-demo image messages under the app's files directory. */
class LocalMediaStore(context: Context) {

    private val root: File = File(context.filesDir, "maptalk-media").also { it.mkdirs() }

    fun save(jpeg: ByteArray, preferredName: String? = null): String {
        val name = preferredName ?: "${UUID.randomUUID()}.jpg"
        val file = File(root, name)
        file.parentFile?.mkdirs()
        file.writeBytes(jpeg)
        return name
    }

    fun delete(relativePath: String) {
        File(root, relativePath).delete()
    }

    fun saveAudio(bytes: ByteArray, ext: String = "m4a"): String {
        val name = "${UUID.randomUUID()}.$ext"
        File(root, name).writeBytes(bytes)
        return name
    }

    fun saveVideo(bytes: ByteArray, ext: String = "mp4"): String {
        val name = "${UUID.randomUUID()}.$ext"
        File(root, name).writeBytes(bytes)
        return name
    }

    fun file(relativePath: String): File = File(root, relativePath)
}
