package io.github.glacier_jellyfin.androidtv.trailer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import io.github.glacier_jellyfin.androidtv.core.log.Log
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** What the view model asks of the embedded player. */
sealed interface YouTubeCommand {
    data object Play : YouTubeCommand
    data object Pause : YouTubeCommand
    data class SeekTo(val positionMs: Long) : YouTubeCommand
    /** A language code, or "" for off. */
    data class Subtitles(val language: String) : YouTubeCommand
}

/**
 * Reports of the embedded player, on the main thread. Each names its video:
 * reports still queued when the screen moves on to another trailer are stale.
 */
interface YouTubeListener {
    /** The IFrame API's player state: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
    fun onYouTubeState(videoId: String, state: Int)
    fun onYouTubeProgress(videoId: String, positionMs: Long, durationMs: Long)
    /** The video's subtitle languages and the one shown (null: off). */
    fun onYouTubeSubtitles(videoId: String, tracks: List<TrailerSubtitle>, shown: String?)
    /** The IFrame API's error code (101/150: embedding not allowed), or [YOUTUBE_NO_WEBVIEW]. */
    fun onYouTubeError(videoId: String, code: Int)
}

/** The device has no usable WebView. */
const val YOUTUBE_NO_WEBVIEW = -1

/**
 * A YouTube video in YouTube's official embedded player (IFrame API), with its
 * own controls hidden: the trailer screen drives it through [commands]. The
 * WebView never takes focus, so the remote keeps talking to Compose.
 *
 * YouTube's embed rules forbid drawing over the player; the screen keeps its
 * OSD out of this view's bounds.
 */
@Composable
fun YouTubePlayer(
    videoId: String,
    /** Subtitles at the start, as in [PlaybackSettings.trailerSubtitles]; later changes arrive as [YouTubeCommand.Subtitles]. */
    subtitles: String,
    commands: Flow<YouTubeCommand>,
    listener: YouTubeListener,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(videoId) {
        runCatching { createWebView(context, videoId, subtitles, listener) }
            .onFailure { Log.w(TAG, "No WebView for the YouTube player", it) }
            .getOrNull()
    }
    if (webView == null) {
        LaunchedEffect(videoId) { listener.onYouTubeError(videoId, YOUTUBE_NO_WEBVIEW) }
        return
    }
    LaunchedEffect(webView) {
        commands.collect { command ->
            val script = when (command) {
                YouTubeCommand.Play -> "player && player.playVideo()"
                YouTubeCommand.Pause -> "player && player.pauseVideo()"
                is YouTubeCommand.SeekTo -> "player && player.seekTo(${command.positionMs / 1000.0}, true)"
                is YouTubeCommand.Subtitles -> "setSubtitles(${JSONObject.quote(command.language)})"
            }
            webView.evaluateJavascript(script, null)
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }
    AndroidView(factory = { webView }, modifier = modifier)
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: Context, videoId: String, subtitles: String, listener: YouTubeListener): WebView =
    WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        // The page only needs the network: no files or content providers of the device.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webChromeClient = object : WebChromeClient() {
            // Without a poster, WebView shows a grey play symbol until the video starts.
            override fun getDefaultVideoPoster(): Bitmap = createBitmap(1, 1)
        }
        webViewClient = object : WebViewClient() {
            // Links inside the player (title, logo) would leave the page; the remote cannot reach them anyway.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        addJavascriptInterface(Bridge(videoId, listener), "Glacier")
        // YouTube refuses embeds without a referrer (error 153): the page claims the project's website as its origin.
        loadDataWithBaseURL("$ORIGIN/", page(videoId, subtitles), "text/html", "utf-8", null)
    }

/** Relays the page's calls, which arrive on a WebView thread, to the main thread. */
private class Bridge(private val videoId: String, private val listener: YouTubeListener) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onState(state: Int) {
        main.post { listener.onYouTubeState(videoId, state) }
    }

    @JavascriptInterface
    fun onProgress(positionMs: Double, durationMs: Double) {
        main.post { listener.onYouTubeProgress(videoId, positionMs.toLong(), durationMs.toLong()) }
    }

    @JavascriptInterface
    fun onSubtitles(tracks: String, shown: String) {
        val list = runCatching {
            val array = JSONArray(tracks)
            (0 until array.length()).map { i ->
                val track = array.getJSONObject(i)
                TrailerSubtitle(track.getString("language"), track.optString("name").ifEmpty { null })
            }.distinctBy { it.language }
        }.getOrDefault(emptyList())
        main.post { listener.onYouTubeSubtitles(videoId, list, shown.ifEmpty { null }) }
    }

    @JavascriptInterface
    fun onError(code: Int) {
        main.post { listener.onYouTubeError(videoId, code) }
    }
}

/**
 * Subtitles go through the captions module, which the IFrame API documents
 * only loosely: cc_load_policy loads it, its "tracklist" and "track" options
 * name the languages, "track" also picks one, and unloading the module turns
 * subtitles off. YouTube loads the module again when playback starts, so
 * "off" is applied again then. A video without the chosen language plays
 * without subtitles. Player texts (such as "auto-generated") follow the
 * device language.
 */
private fun page(videoId: String, subtitles: String) = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#player{position:absolute;inset:0;width:100%;height:100%}</style>
    </head><body>
    <div id="player"></div>
    <script>
    var player;
    // A language code, or '' for off.
    var subtitles = ${JSONObject.quote(subtitles)};
    function captionsLoaded() {
      return player.getOptions().indexOf('captions') >= 0;
    }
    // Subtitle tracks by language code. The tracklist option leaves out auto-generated ones,
    // so the track YouTube loads on its own is added as well.
    var tracks = {};
    function collectTracks() {
      (player.getOption('captions', 'tracklist') || []).forEach(function (t) {
        if (t.languageCode && !tracks[t.languageCode]) tracks[t.languageCode] = t;
      });
      var current = player.getOption('captions', 'track') || {};
      if (current.languageCode && !tracks[current.languageCode]) {
        // The original, never a translation YouTube remembered from an earlier video.
        var original = {};
        for (var key in current) if (key !== 'translationLanguage') original[key] = current[key];
        tracks[current.languageCode] = original;
      }
      return Object.keys(tracks).length > 0;
    }
    function reportSubtitles(shown) {
      Glacier.onSubtitles(JSON.stringify(Object.keys(tracks).map(function (code) {
        return { language: code, name: tracks[code].languageName || tracks[code].displayName || '' };
      })), shown);
    }
    // The tracks can arrive after the captions module, without another event: checked until they are there.
    var listKnown = false;
    // YouTube remembers "off" and then skips cc_load_policy: the module is loaded once by hand.
    var loadAsked = false;
    function hideSubtitles() {
      player.unloadModule('captions');
      player.unloadModule('cc');
    }
    function setSubtitles(language) {
      subtitles = language;
      if (player && listKnown) applySubtitles();
    }
    // Only the chosen language shows, never one YouTube picks.
    function syncSubtitles() {
      if (listKnown) return;
      if (!captionsLoaded()) {
        if (!loadAsked && player.getPlayerState() === 1) {
          loadAsked = true;
          player.loadModule('captions');
        }
        return;
      }
      if (!collectTracks()) return;
      listKnown = true;
      reportSubtitles(applySubtitles());
    }
    // Shows the chosen language or nothing; returns what shows ('' for nothing).
    function applySubtitles() {
      if (subtitles === '' || !tracks[subtitles]) {
        hideSubtitles();
        return '';
      }
      // Without the module, its next onApiChange picks the track.
      if (captionsLoaded()) player.setOption('captions', 'track', tracks[subtitles]);
      else player.loadModule('captions');
      return subtitles;
    }
    // The captions module came or went.
    function onApiChange() {
      if (!listKnown) syncSubtitles();
      else if (captionsLoaded()) applySubtitles();
    }
    function onYouTubeIframeAPIReady() {
      player = new YT.Player('player', {
        width: '100%', height: '100%', videoId: '$videoId',
        playerVars: {
          autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, playsinline: 1, iv_load_policy: 3, cc_load_policy: 1,
          hl: ${JSONObject.quote(Locale.getDefault().language)}, origin: '$ORIGIN'
        },
        events: {
          onReady: function (e) {
            e.target.playVideo();
            setInterval(function () {
              syncSubtitles();
              Glacier.onProgress(player.getCurrentTime() * 1000, (player.getDuration() || 0) * 1000);
            }, 500);
          },
          onStateChange: function (e) {
            // YouTube reloads the module (with what it remembers) when playback starts again.
            if (e.data === 1 && listKnown) applySubtitles();
            Glacier.onState(e.data);
          },
          onApiChange: onApiChange,
          onError: function (e) { Glacier.onError(e.data); }
        }
      });
    }
    </script>
    <script src="https://www.youtube.com/iframe_api"></script>
    </body></html>
""".trimIndent()

private const val ORIGIN = "https://glacier-jellyfin.github.io"
private const val TAG = "YouTubePlayer"
