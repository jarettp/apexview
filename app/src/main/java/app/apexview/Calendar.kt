package app.apexview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val MonthFormat = SimpleDateFormat("MMMM", Locale.getDefault())
private val DayFormat = SimpleDateFormat("MMM d", Locale.getDefault())
private val SessionFormat = SimpleDateFormat("EEE, MMM d · h:mm a", Locale.getDefault())

/**
 * The season at a glance: every weekend by month, finished ones dimmed, the one under way or
 * next marked, with its sessions in local time. Tap a weekend to open it.
 */
@Composable
fun CalendarScreen(app: AppState) {
    val now = System.currentTimeMillis()
    val list = rememberLazyListState()
    val density = LocalDensity.current
    val scrolled by remember {
        derivedStateOf { if (list.firstVisibleItemIndex > 0) Float.MAX_VALUE else list.firstVisibleItemScrollOffset.toFloat() }
    }
    val span = with(density) { 50.dp.toPx() }
    val weekends = app.weekends.sortedBy { it.startMs }
    // The one under way, else the next to come: its sessions are listed under it.
    val focus = weekends.firstOrNull { it.isCurrent(now) } ?: weekends.firstOrNull { it.startMs > now }
    LaunchedEffect(focus?.meetingKey) { focus?.let(app::loadWeekend) }
    // Opens on the weekend that matters now rather than round 1.
    LaunchedEffect(weekends.size) {
        val index = focus?.let { f -> weekends.indexOf(f) } ?: -1
        // Each month heading adds an item before its weekends; close enough to land near it.
        if (index > 2) list.scrollToItem((index + index / 3).coerceAtMost(weekends.size))
    }

    Box(Modifier.fillMaxSize().background(Ink.background)) {
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            item {
                Spacer(Modifier.statusBarsPadding().height(60.dp))
                Text("${app.season} calendar", style = Type.largeTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
                Reminders(app)
                if (weekends.isEmpty()) Text("Loading the calendar…", style = Type.subhead, modifier = Modifier.padding(20.dp))
            }
            weekends.groupBy { MonthFormat.format(Date(it.startMs)) }.forEach { (month, inMonth) ->
                item(key = "month-$month") {
                    Text(month, style = Type.title2, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 6.dp))
                }
                inMonth.forEach { w ->
                    item(key = w.meetingKey) { WeekendRow(app, w, now, focus = w == focus) }
                }
            }
            item { Spacer(Modifier.height(40.dp)) }
        }
        TopBar(
            title = "Calendar",
            fill = { (scrolled / span).coerceIn(0f, 1f) },
            titleIn = { ((scrolled - span * 0.6f) / (span * 0.4f)).coerceIn(0f, 1f) },
            leading = { backdrop -> GlyphButton(Icons.Rounded.ArrowBackIosNew, "Back", backdrop = backdrop) { app.back() } },
        )
    }
}

@Composable
private fun WeekendRow(app: AppState, w: WeekendInfo, now: Long, focus: Boolean) {
    val done = w.isDone(now)
    val fade = if (done) 0.45f else 1f
    Column(
        Modifier
            .fillMaxWidth()
            .pressable(scaleTo = 0.99f) { app.open(Screen.Weekend(w)) }
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Row {
            Text(
                w.round?.let { "$it" } ?: "T",
                style = Type.title2.copy(color = Ink.secondary.copy(alpha = fade)),
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(w.name, style = Type.headline.copy(color = Ink.primary.copy(alpha = fade)))
                Text(
                    "${w.place} · ${DayFormat.format(Date(w.startMs))} – ${DayFormat.format(Date(w.endMs))}",
                    style = Type.subhead.copy(color = Ink.secondary.copy(alpha = fade)),
                )
            }
            when {
                w.isCurrent(now) -> Text("This weekend", style = Type.footnote.copy(color = LIVE_RED))
                focus -> Text(countdown(w.startMs - now), style = Type.footnote.copy(color = Ink.primary))
            }
        }
        // The weekend that matters now lists its sessions, local time.
        if (focus) {
            val sessions = app.weekendContent[w.meetingKey]?.sessions.orEmpty().filter { it.startMs > 0 }
            Column(Modifier.padding(start = 56.dp, top = 8.dp)) {
                sessions.forEach { s ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(s.title, style = Type.subhead.copy(color = if (s.startMs < now) Ink.secondary else Ink.primary), modifier = Modifier.weight(1f))
                        Text(SessionFormat.format(Date(s.startMs)), style = Type.subhead)
                    }
                }
            }
        }
    }
}

private val LIVE_RED = Color(0xFFFF453A)

/** "In 4 days", "In 6 hours", "Soon". */
private fun countdown(ms: Long): String {
    val hours = ms / 3_600_000
    return when {
        hours >= 48 -> "In ${hours / 24} days"
        hours >= 1 -> "In $hours hour${if (hours == 1L) "" else "s"}"
        else -> "Soon"
    }
}

/**
 * The reminders switch: 15 minutes before each session of the coming weekend. Turning it on asks
 * for Android's notification permission first.
 */
@Composable
private fun Reminders(app: AppState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) app.chooseReminders(true) }
    Column(
        Modifier
            .padding(horizontal = 20.dp)
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .background(Ink.surface)
            .padding(vertical = 4.dp)
    ) {
        SwitchRow("Session reminders", app.remindersOn) { on ->
            val needsPermission = on && android.os.Build.VERSION.SDK_INT >= 33 &&
                androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (needsPermission) ask.launch(android.Manifest.permission.POST_NOTIFICATIONS) else app.chooseReminders(on)
        }
        if (app.remindersOn) {
            SwitchRow("Only qualifying and races", app.remindersMainOnly, app::chooseRemindersMainOnly)
        }
    }
    Text(
        "A notification 15 minutes before each session of the coming weekend.",
        style = Type.footnote,
        modifier = Modifier.padding(start = 36.dp, end = 36.dp, top = 8.dp),
    )
}
