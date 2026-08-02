package org.futo.inputmethod.latin.uix.actions.gif

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.common.Constants
import org.futo.inputmethod.latin.uix.Action
import org.futo.inputmethod.latin.uix.ActionHeaderSearch
import org.futo.inputmethod.latin.uix.ActionWindow
import org.futo.inputmethod.latin.uix.LocalKeyboardScheme
import org.futo.inputmethod.latin.uix.actions.clipboard.CLIPBOARD_AUTHORITY
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardPasteRequest
import org.futo.inputmethod.latin.uix.actions.clipboard.ClipboardProviderState
import org.futo.inputmethod.latin.uix.getSetting
import org.futo.inputmethod.latin.uix.setSetting
import org.futo.inputmethod.latin.uix.settings.SettingTextField
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.userSettingDecorationOnly
import androidx.core.net.toUri
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private enum class GifUiState { Loading, Loaded, Empty, Error, NoApiKey }

private suspend fun loadThumbnailBitmap(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
        connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
        val bytes = connection.inputStream.use { it.readBytes() }
        decodeSampledBitmap(bytes, 320)
    } catch (e: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDim || bounds.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.asImageBitmap()
    } catch (e: Exception) {
        null
    }
}

val GifAction = Action(
    icon = R.drawable.gif,
    name = R.string.action_gif_title,
    canShowKeyboard = true,
    simplePressImpl = null,
    windowImpl = { manager, _ ->
        object : ActionWindow() {
            private val searchText = mutableStateOf("")
            private val searching = mutableStateOf(false)
            private val results = mutableStateOf<List<GiphyGif>>(emptyList())
            private val uiState = mutableStateOf(GifUiState.Loading)
            private val errorMessage = mutableStateOf("")
            private val bannerMessage = mutableStateOf("")
            private val thumbnails = mutableStateMapOf<String, ImageBitmap>()
            private var loadJob: Job? = null

            @Composable
            override fun windowName(): String = stringResource(R.string.action_gif_title)

            private fun load(query: String) {
                val context = manager.getContext()
                val apiKey = context.getSetting(GiphyApiKeySetting)
                if (apiKey.isBlank()) {
                    uiState.value = GifUiState.NoApiKey
                    return
                }
                bannerMessage.value = ""
                uiState.value = GifUiState.Loading
                loadJob?.cancel()
                loadJob = manager.getLifecycleScope().launch {
                    GiphyApi.cleanupCache(context)
                    val result = if (query.isBlank()) {
                        GiphyApi.trending(apiKey)
                    } else {
                        GiphyApi.search(apiKey, query)
                    }
                    result.onSuccess { list ->
                        results.value = list
                        uiState.value = if (list.isEmpty()) GifUiState.Empty else GifUiState.Loaded
                    }.onFailure { e ->
                        errorMessage.value = e.message ?: context.getString(R.string.action_gif_error_generic)
                        uiState.value = GifUiState.Error
                    }
                }
            }

            private fun insert(gif: GiphyGif) {
                val context = manager.getContext()
                val url = gif.insertionUrl()
                if (url.isEmpty()) return
                manager.getLifecycleScope().launch {
                    val file = GiphyApi.downloadToCache(context, url, gif.id)
                    if (file == null) {
                        bannerMessage.value = context.getString(R.string.action_gif_download_failed)
                        return@launch
                    }
                    val request = ClipboardProviderState.addRequest(
                        ClipboardPasteRequest(
                            file = file,
                            mimeType = GIF_MIME_TYPE,
                            expiration = System.currentTimeMillis() + 5L * 60L * 1000L
                        )
                    )
                    val uri = "content://${CLIPBOARD_AUTHORITY}/clip/$request".toUri()
                    if (!manager.typeUri(uri, listOf(GIF_MIME_TYPE), true)) {
                        bannerMessage.value = context.getString(R.string.action_gif_insert_failed)
                    }
                }
            }

            @Composable
            override fun WindowContents(keyboardShown: Boolean) {
                val view = LocalView.current
                LaunchedEffect(Unit) {
                    if (uiState.value == GifUiState.Loading && results.value.isEmpty()) {
                        load(searchText.value)
                    }
                }

                Column(Modifier.fillMaxSize()) {
                    val banner = bannerMessage.value
                    if (banner.isNotEmpty()) {
                        Text(
                            banner,
                            modifier = Modifier.fillMaxWidth().padding(8.dp),
                            textAlign = TextAlign.Center,
                            color = LocalKeyboardScheme.current.error
                        )
                    }

                    when (uiState.value) {
                        GifUiState.NoApiKey -> NoApiKeyContent(onSaved = { load(searchText.value) })
                        GifUiState.Loading -> CenteredMessage { CircularProgressIndicator(Modifier.size(48.dp)) }
                        GifUiState.Error -> CenteredMessage {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(errorMessage.value, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(8.dp))
                                TextButton(onClick = { load(searchText.value) }) {
                                    Text(stringResource(R.string.action_gif_retry))
                                }
                            }
                        }
                        GifUiState.Empty -> CenteredMessage {
                            Text(stringResource(R.string.action_gif_no_results), textAlign = TextAlign.Center)
                        }
                        GifUiState.Loaded -> GifGrid(
                            gifs = results.value,
                            thumbnails = thumbnails,
                            onClick = { gif ->
                                insert(gif)
                                manager.performHapticAndAudioFeedback(Constants.CODE_OUTPUT_TEXT, view)
                            }
                        )
                    }
                }
            }

            @Composable
            private fun NoApiKeyContent(onSaved: () -> Unit) {
                val context = LocalContext.current
                val keyText = remember { mutableStateOf("") }
                Column(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.action_gif_no_api_key_title),
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.action_gif_no_api_key_subtitle),
                        textAlign = TextAlign.Center)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = keyText.value,
                        onValueChange = { keyText.value = it },
                        placeholder = { Text(stringResource(R.string.giphy_settings_api_key_placeholder)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        manager.getLifecycleScope().launch {
                            context.setSetting(GiphyApiKeySetting, keyText.value.trim())
                            onSaved()
                        }
                    }) {
                        Text(stringResource(R.string.action_gif_save_api_key))
                    }
                }
            }

            @Composable
            private fun CenteredMessage(content: @Composable () -> Unit) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
            }

            @Composable
            private fun GifGrid(
                gifs: List<GiphyGif>,
                thumbnails: androidx.compose.runtime.snapshots.SnapshotStateMap<String, ImageBitmap>,
                onClick: (GiphyGif) -> Unit
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(120.dp),
                    modifier = Modifier.fillMaxSize().padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(gifs, key = { it.id }) { gif ->
                        GifCell(gif, thumbnails, onClick)
                    }
                }
            }

            @Composable
            private fun GifCell(
                gif: GiphyGif,
                thumbnails: androidx.compose.runtime.snapshots.SnapshotStateMap<String, ImageBitmap>,
                onClick: (GiphyGif) -> Unit
            ) {
                val url = gif.thumbnailUrl()
                LaunchedEffect(url) {
                    if (url.isNotEmpty() && !thumbnails.containsKey(url)) {
                        val bitmap = loadThumbnailBitmap(url)
                        if (bitmap != null) thumbnails[url] = bitmap
                    }
                }
                val bitmap = thumbnails[url]
                Box(
                    modifier = Modifier
                        .height(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(LocalKeyboardScheme.current.keyboardContainer)
                        .clickable { onClick(gif) },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = gif.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }

            @Composable
            override fun WindowTitleBar(rowScope: RowScope) {
                if (searching.value) {
                    with(rowScope) {
                        ActionHeaderSearch(searchText, Modifier.weight(1.0f),
                            stringResource(R.string.action_gif_search_placeholder))
                        IconButton(onClick = { load(searchText.value) }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_gif_search_placeholder))
                        }
                        IconButton(onClick = {
                            searching.value = false
                            searchText.value = ""
                            load("")
                        }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                } else {
                    super.WindowTitleBar(rowScope)
                    IconButton(onClick = { searching.value = true }) {
                        Icon(Icons.Default.Search, contentDescription = stringResource(R.string.action_gif_search_placeholder))
                    }
                }
            }
        }
    },
    settingsMenu = UserSettingsMenu(
        title = R.string.action_gif_title,
        navPath = "actions/gif",
        registerNavPath = true,
        settings = listOf(
            UserSetting(
                name = R.string.giphy_settings_api_key,
                subtitle = R.string.giphy_settings_api_key_subtitle,
                component = {
                    SettingTextField(
                        title = stringResource(R.string.giphy_settings_api_key),
                        placeholder = stringResource(R.string.giphy_settings_api_key_placeholder),
                        field = GiphyApiKeySetting
                    )
                }
            ),
            userSettingDecorationOnly {
                Text(
                    stringResource(R.string.giphy_settings_api_key_help),
                    modifier = Modifier.padding(16.dp)
                )
            }
        )
    )
)
