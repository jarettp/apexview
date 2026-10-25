package app.apexview

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Records where a control is on screen, so the help overlay can label it in place. */
fun Modifier.helpAnchor(app: AppState, key: String): Modifier =
    onGloballyPositioned { app.helpAnchors[key] = it.boundsInRoot() }

private val labelShadow = Shadow(Color.Black, blurRadius = 8f)

/** What each top-bar button does. */
private val BAR_HELP = listOf(
    "close" to ("Close" to "stop watching"),
    "lock" to ("Arrange" to "move, resize, snap"),
    "add" to ("Add" to "more feeds, up to 4"),
    "layout" to ("Layout" to "presets, save your own"),
    "radio-button" to ("Radio" to "team radio, transcribed"),
    "map" to ("Map" to "style, timing, opacity"),
)

/**
 * The explanation overlay: the viewer dims and each control gets a label where it actually is,
 * so the hidden gestures (tap, double-tap, hold) are shown on the feeds themselves. Opened from
 * the ? in the top bar, and once by itself the first time a stream plays. Tap anywhere to close.
 *
 * [feedScale] is how far the feeds are shrunk under the controls; feed labels follow the feeds'
 * real positions (their own layout, scaled the same way), since a shrinking layer doesn't report
 * new bounds.
 */
@Composable
fun HelpOverlay(app: AppState, width: Float, height: Float, feedScale: Float, compact: Boolean) {
    AnimatedVisibility(
        app.helpOpen,
        enter = fadeIn(tween(Motion.SCREEN_MS, easing = EaseOut)),
        exit = fadeOut(tween(Motion.FADE_MS, easing = EaseOut)),
    ) {
        val density = LocalDensity.current
        val anchors = app.helpAnchors
        val gap = with(density) { 8.dp.toPx() }
        val row = with(density) { 26.dp.toPx() }
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM))
                .pointerInput(Unit) { detectTapGestures { app.closeHelp() } }
        ) {
            // Top bar: a staircase, one row per button from left to right, each label hanging off
            // a line from its button. Labels on the right half end at their line; on the left
            // half they start at it. Nothing shares a row, so nothing overlaps.
            val bar = if (!app.chromeVisible) emptyList() else
                BAR_HELP.mapNotNull { (key, text) -> anchors[key]?.let { it to text } }.sortedBy { it.first.center.x }
            val barTop = bar.maxOfOrNull { it.first.bottom } ?: 0f
            Canvas(Modifier.fillMaxSize()) {
                bar.forEachIndexed { i, (r, _) ->
                    drawLine(
                        Color.White.copy(alpha = 0.5f),
                        Offset(r.center.x, r.bottom + 2f),
                        Offset(r.center.x, barTop + gap + row * i + row / 2),
                        1.dp.toPx(),
                    )
                }
            }
            bar.forEachIndexed { i, (r, text) ->
                val right = r.center.x > width / 2
                Callout(r.center.x, barTop + gap + row * i, width, side = if (right) Side.END_AT else Side.START_AT) {
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = Color.White)) { append(text.first) }
                            append("  ")
                            append(text.second)
                        },
                        style = Type.footnote.copy(color = Color.White.copy(alpha = 0.8f), shadow = labelShadow),
                        maxLines = 1,
                    )
                }
            }
            val belowBar = if (bar.isEmpty()) 0f else barTop + gap + row * bar.size + gap

            // Where the feeds really are on screen.
            val feeds = app.tiles.map { t -> t to onScreen(Rect(t.x, t.y, t.x + t.w, t.y + t.h), width, height, feedScale) }
                .filter { (_, r) -> r.left < width && r.right > 0f }
            val radio = anchors["radio"]?.takeIf { app.radioOpen }
            // Big and small feeds only mean something on the big screen in main + strip.
            val main = feeds.firstOrNull { (t, _) -> !compact && app.tiles.size > 1 && app.isMainTile(t) }

            // The legend sits in the main feed's open space below the bar labels, else mid-screen.
            val area = main?.second?.let { Rect(it.left, maxOf(it.top, belowBar), it.right, it.bottom) }
                ?.takeIf { it.height > row * 9 }
                ?: Rect(0f, belowBar, width, height - with(density) { 90.dp.toPx() })
            val legendWidth = minOf(area.width * 0.94f, with(density) { LEGEND_MAX.toPx() })
            // Centred in its space, but never up into the top bar's labels: a legend taller than
            // the space starts just below them and runs on down.
            Callout(area.center.x, area.center.y, width, side = Side.CENTRE, maxWidth = legendWidth, minTop = belowBar) {
                Legend(compact = main == null && compact, swapLine = main != null, radio = radio != null)
            }

            anchors["mute"]?.takeIf { app.chromeVisible && !app.arranging }?.let { r ->
                Callout(r.center.x, r.top - gap, width, side = Side.ABOVE) { Title("Mute all") }
            }
        }
    }
}

private const val SCRIM = 0.88f
private val LEGEND_MAX = 600.dp
private val TERM_WIDTH = 120.dp

/**
 * The key to the hidden gestures, set like a printed legend: the action on the left, what it
 * does on the right, in short groups.
 */
@Composable
private fun Legend(compact: Boolean, swapLine: Boolean, radio: Boolean) {
    Column {
        Group("Feeds")
        Entry("Tap", "a feed, then a car on the map: its onboard goes there")
        when {
            swapLine -> Entry("Double-tap", "a small feed: it swaps into the big spot")
            compact -> Entry("Double-tap", "a feed: it fills the screen; again to go back")
        }
        Entry("Hold", "a feed: switch it, take its audio, pin it")
        Entry("Tap the picture", "show or hide these controls")
        Group("Map")
        Entry("Tap a car", "bring up its onboard")
        if (radio) {
            Group("Team radio")
            Entry("Tap a clip", "hear it; drag the panel's title to move it")
        }
        Group("Leaving")
        Entry("Swipe home", "keep watching in a small window")
        Text(
            "Tap anywhere to close. The ? in the top bar brings this back.",
            style = Type.caption.copy(color = Color.White.copy(alpha = 0.55f)),
            modifier = Modifier.padding(top = 22.dp, start = TERM_WIDTH + 14.dp),
        )
    }
}

/** A section header: full white, set at the legend's left edge above its entries. */
@Composable
private fun Group(title: String) {
    Text(
        title,
        style = Type.title2.copy(color = Color.White),
        modifier = Modifier.padding(top = 22.dp, bottom = 6.dp),
    )
}

@Composable
private fun Entry(term: String, meaning: String) {
    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 3.dp)) {
        Text(
            term,
            style = Type.body.copy(color = Color.White, fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.End,
            modifier = Modifier.width(TERM_WIDTH),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
        Text(meaning, style = Type.body.copy(color = Color.White.copy(alpha = 0.82f)), modifier = Modifier.weight(1f, fill = false))
    }
}

/** Where a feed's box lands once the feed layer is shrunk toward the bottom centre by [scale]. */
internal fun onScreen(r: Rect, width: Float, height: Float, scale: Float) = Rect(
    width / 2 + (r.left - width / 2) * scale,
    height + (r.top - height) * scale,
    width / 2 + (r.right - width / 2) * scale,
    height + (r.bottom - height) * scale,
)

@Composable
private fun Title(text: String) {
    Text(text, style = Type.headline.copy(color = Color.White, shadow = labelShadow), textAlign = TextAlign.Center)
}

@Composable
private fun Line(text: String) {
    Text(text, style = Type.footnote.copy(color = Color.White.copy(alpha = 0.85f), shadow = labelShadow), textAlign = TextAlign.Center)
}

private enum class Side { START_AT, END_AT, CENTRE, ABOVE }

/**
 * A label placed against the point ([x], [y]): starting at it, ending at it, centred on it, or
 * centred above it. Always kept on screen.
 */
@Composable
private fun Callout(
    x: Float,
    y: Float,
    screenWidth: Float,
    side: Side,
    maxWidth: Float = Float.MAX_VALUE,
    /** The highest a label may start. */
    minTop: Float = 0f,
    content: @Composable () -> Unit,
) {
    val margin = with(LocalDensity.current) { 8.dp.toPx() }
    Layout(content = { Column(horizontalAlignment = Alignment.CenterHorizontally) { content() } }) { measurables, constraints ->
        val limit = minOf(maxWidth, screenWidth - 2 * margin).roundToInt().coerceAtLeast(1)
        val p = measurables.first().measure(Constraints(maxWidth = limit))
        layout(constraints.maxWidth, constraints.maxHeight) {
            val wanted = when (side) {
                Side.START_AT -> x + margin
                Side.END_AT -> x - p.width - margin
                Side.CENTRE, Side.ABOVE -> x - p.width / 2f
            }
            val left = wanted.coerceIn(margin, (screenWidth - margin - p.width).coerceAtLeast(margin))
            val top = when (side) {
                Side.ABOVE -> y - p.height
                Side.CENTRE -> maxOf(minTop, y - p.height / 2f)
                else -> y
            }
            p.place(left.roundToInt(), top.roundToInt())
        }
    }
}
