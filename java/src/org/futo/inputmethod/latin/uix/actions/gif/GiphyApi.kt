package org.futo.inputmethod.latin.uix.actions.gif

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.futo.inputmethod.latin.uix.SettingsKey
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

val GiphyApiKeySetting = SettingsKey(
    key = stringPreferencesKey("giphy_api_key"),
    default = ""
)

const val GIF_MIME_TYPE = "image/gif"

@Serializable
data class GiphyImage(
    val url: String = "",
    val width: String = "0",
    val height: String = "0",
    val size: String = "0"
)

@Serializable
data class GiphyImages(
    @SerialName("fixed_width") val fixedWidth: GiphyImage? = null,
    @SerialName("fixed_width_still") val fixedWidthStill: GiphyImage? = null,
    @SerialName("fixed_width_downsampled") val fixedWidthDownsampled: GiphyImage? = null,
    @SerialName("downsized") val downsized: GiphyImage? = null
)

@Serializable
data class GiphyGif(
    val id: String = "",
    val title: String? = null,
    val images: GiphyImages = GiphyImages()
) {
    fun thumbnailUrl(): String =
        images.fixedWidthStill?.url
            ?: images.fixedWidthDownsampled?.url
            ?: images.fixedWidth?.url
            ?: ""

    fun insertionUrl(): String =
        images.fixedWidth?.url
            ?: images.downsized?.url
            ?: images.fixedWidthStill?.url
            ?: ""
}

@Serializable
data class GiphyResponse(
    val data: List<GiphyGif> = emptyList()
)

object GiphyApi {
    private const val BASE_URL = "https://api.giphy.com/v1/gifs"
    private const val LIMIT = 24
    private const val RATING = "g"
    private const val TIMEOUT_MS = 8000

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun trending(apiKey: String): Result<List<GiphyGif>> =
        get("$BASE_URL/trending?api_key=${encode(apiKey)}&limit=$LIMIT&rating=$RATING")

    suspend fun search(apiKey: String, query: String): Result<List<GiphyGif>> =
        get("$BASE_URL/search?api_key=${encode(apiKey)}&q=${encode(query)}&limit=$LIMIT&rating=$RATING")

    private suspend fun get(url: String): Result<List<GiphyGif>> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                return@withContext Result.failure(IOException("GIPHY returned HTTP $code"))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            Result.success(json.decodeFromString<GiphyResponse>(body).data)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }

    suspend fun downloadToCache(context: Context, url: String, id: String): File? =
        withContext(Dispatchers.IO) {
            var connection: HttpURLConnection? = null
            var tempFile: File? = null
            try {
                connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    requestMethod = "GET"
                }
                if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext null
                }
                val dir = File(context.cacheDir, "gifs").apply { mkdirs() }
                tempFile = File(dir, "$id.gif.part")
                connection.inputStream.use { input ->
                    tempFile.outputStream().use { out -> input.copyTo(out) }
                }
                val finalFile = File(dir, "$id.gif")
                if (finalFile.exists()) finalFile.delete()
                if (tempFile.renameTo(finalFile)) finalFile else {
                    tempFile.delete()
                    null
                }
            } catch (e: Exception) {
                tempFile?.delete()
                null
            } finally {
                connection?.disconnect()
            }
        }

    suspend fun cleanupCache(context: Context) = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.cacheDir, "gifs")
            val files = dir.listFiles() ?: return@withContext
            val cutoff = System.currentTimeMillis() - 60L * 60L * 1000L
            for (file in files) {
                if (file.name.endsWith(".part") || file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
