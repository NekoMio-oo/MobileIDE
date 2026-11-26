package dev.mobileide.data.runtime

import dev.mobileide.domain.runtime.RemoteFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads runtime artifacts with progress. Files land in the download cache
 * and are only moved into place after the size check; checksums are verified by
 * the installer before anything is extracted.
 */
class RuntimeDownloader(private val directory: File) {

    suspend fun download(
        remote: RemoteFile,
        onProgress: (bytesDone: Long, bytesTotal: Long?) -> Unit
    ): File = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val target = File(directory, remote.fileName)
        val partial = File(directory, "${remote.fileName}.part")
        partial.delete()

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(remote.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "MobileIDE")
            }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${remote.fileName}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: remote.sizeBytes

            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(done, total)
                    }
                }
            }

            remote.sizeBytes?.let { expected ->
                if (partial.length() != expected) {
                    throw IOException(
                        "${remote.fileName} is ${partial.length()} bytes, expected $expected"
                    )
                }
            }
            target.delete()
            if (!partial.renameTo(target)) {
                throw IOException("Could not store ${remote.fileName}")
            }
            target
        } finally {
            connection?.disconnect()
            partial.delete()
        }
    }
}
