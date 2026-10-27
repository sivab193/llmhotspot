package dev.llmhotspot.app.model

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CatalogModel(
    val id: String,
    val name: String,
    val ramTierGb: Int,
    val sizeBytes: Long,
    val url: String,
    val sha256: String,
    val capabilities: List<String>,
)

class ModelRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("llmhotspot", Context.MODE_PRIVATE)
    private val modelDir = (context.getExternalFilesDir("models") ?: File(context.filesDir, "models")).apply { mkdirs() }

    val catalog: List<CatalogModel> by lazy {
        Json.decodeFromString(context.assets.open("model-catalog.json").bufferedReader().use { it.readText() })
    }

    fun installed(): List<File> = modelDir.listFiles { file -> file.isFile && file.extension.equals("gguf", true) }
        ?.sortedBy { it.name } ?: emptyList()

    fun selectedPath(): String? = prefs.getString("model_path", null)?.takeIf { File(it).isFile }
    fun select(file: File) { prefs.edit().putString("model_path", file.absolutePath).apply() }

    fun enqueue(model: CatalogModel): Long {
        require(model.url.startsWith("https://") && model.sha256.length == 64) { "This catalog entry is not download-enabled" }
        val partial = File(modelDir, "${model.id}.download").apply { delete() }
        val request = DownloadManager.Request(Uri.parse(model.url))
            .setTitle(model.name)
            .setDescription("Downloading GGUF model")
            .setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            .setAllowedOverMetered(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationUri(Uri.fromFile(partial))
        return context.getSystemService(DownloadManager::class.java).enqueue(request).also { id ->
            prefs.edit().putLong("download_${model.id}", id).apply()
        }
    }

    fun pendingDownload(): Pair<Long, CatalogModel>? = catalog.firstNotNullOfOrNull { model ->
        val id = prefs.getLong("download_${model.id}", -1L)
        if (id >= 0) id to model else null
    }

    fun clearDownload(model: CatalogModel) { prefs.edit().remove("download_${model.id}").apply() }

    data class DownloadState(val status: Int, val downloaded: Long, val total: Long, val reason: Int)

    fun downloadState(id: Long): DownloadState? {
        val manager = context.getSystemService(DownloadManager::class.java)
        return manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            DownloadState(
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)),
            )
        }
    }

    suspend fun finalizeDownload(model: CatalogModel): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val partial = File(modelDir, "${model.id}.download")
            require(partial.isFile) { "Downloaded model file was not found" }
            val digest = MessageDigest.getInstance("SHA-256")
            partial.inputStream().buffered(1024 * 1024).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual.equals(model.sha256, ignoreCase = true)) { "Model checksum mismatch; the download was removed" }
            val destination = File(modelDir, "${model.id}.gguf")
            destination.delete()
            require(partial.renameTo(destination)) { "Unable to finalize model download" }
            select(destination)
            clearDownload(model)
            destination
        }.onFailure { File(modelDir, "${model.id}.download").delete() }
            .also { clearDownload(model) }
    }

    suspend fun import(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.endsWith(".gguf", true) }
                ?: "model-${System.currentTimeMillis()}.gguf"
            val destination = File(modelDir, displayName.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            try {
                context.contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "Unable to open selected model" }
                    destination.outputStream().use { input.copyTo(it, 1024 * 1024) }
                }
                require(destination.length() > 0) { "Selected model was empty" }
                select(destination)
                destination
            } catch (error: Throwable) {
                destination.delete()
                throw error
            }
        }
    }

    fun remove(file: File): Boolean {
        if (selectedPath() == file.absolutePath) prefs.edit().remove("model_path").apply()
        return file.delete()
    }
}
