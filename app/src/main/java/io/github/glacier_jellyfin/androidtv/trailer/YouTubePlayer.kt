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

/** What the view model asks of the embedded player. */
sealed interface YouTubeCommand {
    data object Play : YouTubeCommand
    data object Pause : YouTubeCommand
    data class SeekTo(val positionMs: Long) : YouTubeCommand
}

/**
 * Reports of the embedded player, on the main thread. Each names its video:
 * reports still queued when the screen moves on to another trailer are stale.
 */
interface YouTubeListener {
    /** The IFrame API's player state: -1 unstarted, 0 ended, 1 playing, 2 paused, 3 buffering, 5 cued. */
    fun onYouTubeState(videoId: String, state: Int)
    fun onYouTubeProgress(videoId: String, positionMs: Long, durationMs: Long)
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
    commands: Flow<YouTubeCommand>,
    listener: YouTubeListener,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember(videoId) {
        runCatching { createWebView(context, videoId, listener) }
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
private fun createWebView(context: Context, videoId: String, listener: YouTubeListener): WebView =
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
        loadDataWithBaseURL("$ORIGIN/", page(videoId), "text/html", "utf-8", null)
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
    fun onError(code: Int) {
        main.post { listener.onYouTubeError(videoId, code) }
    }
}

private fun page(videoId: String) = """
    <!doctype html>
    <html><head>
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <style>html,body{margin:0;width:100%;height:100%;background:#000;overflow:hidden}#player{position:absolute;inset:0;width:100%;height:100%}</style>
    </head><body>
    <div id="player"></div>
    <script>
    var player;
    function onYouTubeIframeAPIReady() {
      player = new YT.Player('player', {
        width: '100%', height: '100%', videoId: '$videoId',
        playerVars: { autoplay: 1, controls: 0, disablekb: 1, fs: 0, rel: 0, playsinline: 1, iv_load_policy: 3, origin: '$ORIGIN' },
        events: {
          onReady: function (e) {
            e.target.playVideo();
            setInterval(function () {
              Glacier.onProgress(player.getCurrentTime() * 1000, (player.getDuration() || 0) * 1000);
            }, 500);
          },
          onStateChange: function (e) { Glacier.onState(e.data); },
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
