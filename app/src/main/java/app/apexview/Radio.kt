package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Short and wide, to sit in the strip of space under or between the feeds: two clips tall. */
private val PANEL_MAX_WIDTH = 560.dp
private val ROW_HEIGHT = 50.dp
private val HEADER_HEIGHT = 36.dp
private val PANEL_MAX_HEIGHT = HEADER_HEIGHT + ROW_HEIGHT * 2 + 4.dp
/** A clip this new (by the picture's clock) flashes once as it arrives. */
private const val FRESH_MS = 3_000L

/**
 * Team radio as it comes in, oldest at the top and the newest at the bottom, where the list
 * follows it; each clip is marked with the lap the driver was on. Follows
 * the picture like the map does, so nothing shows before it's happened on screen. Tap a clip to
 * hear it over the feeds; drag the title to move the panel.
 */
@Composable
fun RadioPanel(app: AppState, timing: SessionTiming, width: Float, height: Float) {
    val density = LocalDensity.current
    // Twice a second is plenty for a list.
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            now = app.mapTimeMs() ?: 0L
            delay(500)
        }
    }
    // Transcribe while the panel is up.
    LaunchedEffect(Unit) { app.transcribeRadio() }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) app.retryTranscripts() }
    val heard = timing.radio.count { it.utc <= now }
    val clips = remember(timing, heard) { timing.radio.take(heard) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    // A new clip brings the list down to it.
    LaunchedEffect(clips.size) { if (clips.isNotEmpty()) list.animateScrollToItem(clips.lastIndex) }

    val panelWidth = with(density) { minOf(PANEL_MAX_WIDTH, (width.toDp() - 32.dp)) }
    if (app.radioX < 0f) {
        with(density) {
            val w = panelWidth.toPx()
            val h = PANEL_MAX_HEIGHT.toPx()
            val saved = app.savedRadioSpot()
            // The biggest gap the feeds leave, if the panel fits in it; else above the controls.
            val gap = Layouts.largestFree(app.tileSlots(), width, height)?.takeIf { it.h >= h }
            when {
                saved != null -> {
                    app.radioX = saved.first * width
                    app.radioY = saved.second * height
                }
                gap != null -> {
                    app.radioX = gap.x + (gap.w - w).coerceAtLeast(0f) / 2
                    app.radioY = gap.y + (gap.h - h) / 2
                }
                else -> {
                    app.radioX = (width - w) / 2
                    app.radioY = height - h - 120.dp.toPx()
                }
            }
            app.radioX = app.radioX.coerceIn(0f, (width - w).coerceAtLeast(0f))
            app.radioY = app.radioY.coerceIn(0f, (height - h).coerceAtLeast(0f))
        }
    }
    Column(
        Modifier
            .offset { IntOffset(app.radioX.roundToInt(), app.radioY.roundToInt()) }
            .helpAnchor(app, "radio")
            .width(panelWidth)
            .heightIn(max = PANEL_MAX_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .background(Color.Black.copy(alpha = 0.72f))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures(onDragEnd = { app.storeRadioSpot(width, height) }) { change, drag ->
                        change.consume()
                        app.radioX = (app.radioX + drag.x).coerceIn(0f, width - panelWidth.toPx())
                        app.radioY = (app.radioY + drag.y).coerceIn(0f, height - 60.dp.toPx())
                    }
                }
                .height(HEADER_HEIGHT)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Team radio", style = Type.headline, modifier = Modifier.weight(1f))
            Box(Modifier.size(36.dp).pressable(onClick = app::toggleRadio), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Hide team radio", tint = Ink.secondary, modifier = Modifier.size(18.dp))
            }
        }
        app.transcriptBlocked?.let { note ->
            val permission = note == AppState.PERMISSION_NOTE
            Text(
                if (permission) "$note Tap to allow." else note,
                style = Type.caption,
                modifier = Modifier
                    .then(if (permission) Modifier.pressable { ask.launch(android.Manifest.permission.RECORD_AUDIO) } else Modifier)
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )
        }
        if (clips.isEmpty()) {
            Text(
                if (timing.radio.isEmpty()) "No radio in this session yet." else "Radio shows up here as it happens.",
                style = Type.footnote,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            )
            return@Column
        }
        LazyColumn(Modifier.padding(bottom = 4.dp), state = list) {
            items(clips, key = { it.path }) { clip -> RadioRow(app, timing, clip, fresh = now - clip.utc < FRESH_MS) }
        }
    }
}

@Composable
private fun RadioRow(app: AppState, timing: SessionTiming, clip: RadioClip, fresh: Boolean) {
    val driver = timing.drivers[clip.number]
    val playing = app.radioPlaying?.path == clip.path
    val lap = timing.laps[clip.number]?.lapAt(clip.utc)
    val text = app.transcripts[clip.path]
    // Arriving clips flash once, softly.
    val flash = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(clip.path) {
        if (fresh) {
            flash.animateTo(1f, androidx.compose.animation.core.tween(Motion.FADE_MS, easing = EaseOut))
            flash.animateTo(0f, androidx.compose.animation.core.tween(Motion.SCREEN_MS * 2, easing = EaseOut))
        }
    }
    // One line per clip: who and when on the left, what was said beside it.
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .pressable(scaleTo = 0.99f) { app.playRadio(clip) }
            .background(Color.White.copy(alpha = if (playing) 0.1f else 0.18f * flash.value))
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(driver?.color?.let { Color(it) } ?: Color.White)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.width(54.dp)) {
            Text(driver?.tla ?: clip.number, style = Type.subhead.copy(color = Ink.primary))
            Text(lap?.let { "Lap $it" } ?: "", style = Type.caption)
        }
        Text(
            when {
                text != null && text != AppState.NO_WORDS -> "\u201C$text\u201D"
                app.transcribing == clip.path -> "Transcribing…"
                else -> ""
            },
            style = Type.footnote.copy(color = if (text != null) Ink.primary else Ink.secondary),
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Icon(
            if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
            if (playing) "Stop" else "Play",
            tint = Ink.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}
