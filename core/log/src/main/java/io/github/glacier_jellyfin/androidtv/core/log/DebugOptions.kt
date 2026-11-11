package io.github.glacier_jellyfin.androidtv.core.log

/** Switches for trying the app out; only debug builds change them (see `GlacierApplication`), the defaults are the app. */
object DebugOptions {

    /** A transcode is made for the display's size; off: the server keeps the file's size and the display scales it down. */
    @Volatile
    var fitDisplay: Boolean = true

    /** A ladder stays on the tier it starts with: for comparing the picture of two kinds of stream. */
    @Volatile
    var fixedTier: Boolean = false

    /** Every load of a segment or playlist and every change of the player's state goes to the log. */
    @Volatile
    var segmentLog: Boolean = false

    /** 25 s after the first picture the player asks for the next segments at once (the probe); it loads the server meanwhile. */
    @Volatile
    var probe: Boolean = false

    /** The segment probe asks for segments played long ago instead of the next ones: they exist, so no wait for ffmpeg. */
    @Volatile
    var probeOld: Boolean = false

    /** Playback pauses at its first picture, so two streams can be compared at the same frame. */
    @Volatile
    var pauseOnStart: Boolean = false
}
