package app.apexview

import android.content.Context
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DummyExoMediaDrm
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.UnsupportedDrmException
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * One feed: its own ExoPlayer and its own Widevine session, licensed by F1's license server
 * with the user's tokens. [status] is the diagnostics line shown on the tile.
 */
@OptIn(UnstableApi::class)
class FeedPlayer(
    context: Context,
    stream: PlayInfo,
    ascendon: String,
    entitlement: String,
    userAgent: String,
    forceL3: Boolean,
    /** Off by default: the international feed carries French captions. */
    subtitles: Boolean,
    /** ISO 639 code, such as "en", for the commentary track. */
    commentary: String,
) {
    var status by mutableStateOf("Starting")
        private set

    val player: ExoPlayer

    /** Distance from the leading tile in ms, set by the sync loop. Null for the leader. */
    var driftMs: Long? = null

    private var state = "Starting"
    private var size = ""
    private var decoder = ""
    private var licensed = false
    private var speed = 1f
    private var lastDropped = 0
    private var dropsPerSecond = 0
    @Volatile private var error: String? = null
    /** Why this feed isn't playing, in plain words, for the tile to show. Null while it's fine. */
    var problem by mutableStateOf<String?>(null)
        private set

    // Written on the playback thread when the DRM session opens.
    @Volatile private var drmLevel = ""

    init {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)

        val licenseCallback = HttpMediaDrmCallback(stream.licenseUrl, http).apply {
            setKeyRequestProperty("ascendontoken", ascendon)
            setKeyRequestProperty("entitlementtoken", entitlement)
        }

        // Software DRM (L3) is what desktop Chrome gets. It skips the phone's limited pool of
        // secure hardware decoders, which is what allows several feeds at once.
        val drmProvider = ExoMediaDrm.Provider { uuid ->
            try {
                FrameworkMediaDrm.newInstance(uuid).also { drm ->
                    if (forceL3) {
                        try {
                            drm.setPropertyString("securityLevel", "L3")
                        } catch (e: Exception) {
                            error = "Phone refused software DRM: ${e.message}"
                        }
                    }
                    drmLevel = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("?")
                }
            } catch (e: UnsupportedDrmException) {
                error = "Widevine unavailable on this phone"
                DummyExoMediaDrm()
            }
        }

        val drmManager = DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, drmProvider)
            .build(licenseCallback)

        val sources = DefaultMediaSourceFactory(http).setDrmSessionManagerProvider { drmManager }

        player = ExoPlayer.Builder(context).setMediaSourceFactory(sources).build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !subtitles)
            .setPreferredAudioLanguage(commentary)
            .setPreferredTextLanguage(commentary)
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                state = when (playbackState) {
                    Player.STATE_BUFFERING -> "Buffering"
                    Player.STATE_READY -> "Playing"
                    Player.STATE_ENDED -> "Ended"
                    else -> "Stopped"
                }
                refresh()
            }

            override fun onPlayerError(e: PlaybackException) {
                error = "${e.errorCodeName}: ${describe(e)}"
                problem = explain(e)
                refresh()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) problem = null
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0) size = "${videoSize.width}x${videoSize.height}"
                refresh()
            }
        })
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                decoder = decoderName
                refresh()
            }

            override fun onDrmKeysLoaded(eventTime: AnalyticsListener.EventTime) {
                licensed = true
                refresh()
            }
        })

        val isHls = stream.url.substringBefore('?').endsWith(".m3u8") || "HLS" in stream.streamType
        player.setMediaItem(
            MediaItem.Builder()
                .setUri(stream.url)
                .setMimeType(if (isHls) MimeTypes.APPLICATION_M3U8 else MimeTypes.APPLICATION_MPD)
                .build()
        )
        player.playWhenReady = true
        player.prepare()
    }

    fun setAudible(audible: Boolean) {
        player.volume = if (audible) 1f else 0f
    }

    /** Small speed changes let a follower catch up without a visible jump. */
    fun setSpeed(value: Float) {
        if (value == speed) return
        speed = value
        player.setPlaybackSpeed(value)
    }

    /**
     * The wall-clock time (epoch ms) of the frame on screen, when the stream carries one
     * (DASH availabilityStartTime or HLS program date time). Null otherwise.
     */
    fun wallClockMs(): Long? = windowStartMs()?.let { it + player.currentPosition }

    /** The wall-clock time (epoch ms) of position zero, when the stream carries a clock. */
    fun windowStartMs(): Long? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val window = timeline.getWindow(player.currentMediaItemIndex, Timeline.Window())
        if (window.windowStartTimeMs == C.TIME_UNSET) return null
        return window.windowStartTimeMs
    }

    /** Rebuilds the diagnostics line. Called on player events and once a second by the tile. */
    fun refresh() {
        val dropped = player.videoDecoderCounters?.droppedBufferCount ?: 0
        if (dropped >= lastDropped) dropsPerSecond = dropped - lastDropped
        lastDropped = dropped

        status = error ?: listOfNotNull(
            state,
            size.ifEmpty { null },
            when {
                state != "Playing" -> null
                dropsPerSecond == 0 -> "smooth"
                else -> "$dropsPerSecond drops/s"
            },
            if (licensed) null else drmLevel.ifEmpty { null }?.let { "DRM $it" },
            driftMs?.let { "sync %+.1fs".format(it / 1000f) },
            if (wallClockMs() == null && state == "Playing") "no stream clock" else null,
        ).joinToString(" · ")
    }

    fun release() = player.release()

    /**
     * What a playback error means for the viewer. F1 refusing the licence or the stream is almost
     * always the account's plan not covering it; anything else gets F1's own error name.
     */
    private fun explain(e: PlaybackException): String {
        val code = generateSequence(e as Throwable) { it.cause }
            .firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.responseCode
        val refused = code == 401 || code == 403 ||
            e.errorCode in PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
        return if (refused) "F1 TV won't play this on your account. Its plan may not include this session."
        else "This feed stopped: ${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}."
    }

    private fun describe(e: PlaybackException): String {
        val chain = generateSequence(e as Throwable) { it.cause }.toList()
        chain.firstNotNullOfOrNull { it as? HttpDataSource.InvalidResponseCodeException }?.let {
            return "HTTP ${it.responseCode} from ${it.dataSpec.uri.host}"
        }
        val root = chain.last()
        return "${root.javaClass.simpleName}: ${root.message}"
    }
}
