package app.maptalk.data

import android.content.Context
import java.io.File
import java.util.UUID

/** Files for local-demo image messages under the app's files directory. */
class LocalMediaStore(context: Context) {

    private val root: File = File(context.filesDir, "maptalk-media").also { it.mkdirs() }

    fun save(jpeg: ByteArray, preferredName: String? = null): String {
        val name = preferredName ?: "${UUID.randomUUID()}.jpg"
        File(root, name).writeBytes(jpeg)
        return name
    }

    fun file(relativePath: String): File = File(root, relativePath)
}
