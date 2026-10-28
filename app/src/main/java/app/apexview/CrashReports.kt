package app.apexview

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.net.URLEncoder

/**
 * Crash reports the person chooses to send. A crash writes its error to a file on the phone and
 * nothing more. On the next launch a prompt says so and can open a GitHub issue with the error
 * filled in, which the person reads, edits and submits themselves, or doesn't. Nothing leaves the
 * phone otherwise: no analytics, no uploads in the background.
 */
object CrashReports {
    private const val FILE = "last-crash.txt"
    /** GitHub's new-issue link carries the text; past a few thousand characters it gets refused. */
    private const val MAX_BODY = 6000

    /** Catches crashes on any thread, notes them, then lets Android close the app as usual. */
    fun install(context: Context) {
        val file = File(context.filesDir, FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { file.writeText("${about()}\n${java.time.Instant.now()}\n\n${scrub(error.stackTraceToString())}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** The last crash's report, if there is one waiting. */
    fun pending(context: Context): String? = File(context.filesDir, FILE).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    /** The app and the phone: nothing about the person or what they watched. */
    fun about() = "Apexview Fork ${BuildConfig.VERSION_NAME} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    /** Sign-in tokens and the signed parts of stream addresses never go into a report. */
    private fun scrub(text: String) = text
        .replace(Regex("""eyJ[\w-]+\.[\w-]+\.[\w-]+"""), "[token]")
        .replace(Regex("""(https?://[^\s?]+)\?\S+"""), "$1?[…]")

    /** Opens a new issue on the app's GitHub repository, filled in. Nothing is sent until the person submits it. */
    fun openIssue(context: Context, title: String, body: String) {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val url = "https://github.com/${BuildConfig.UPDATE_REPO}/issues/new?title=${enc(title)}&body=${enc(body.take(MAX_BODY))}"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** "Report a problem" from Settings: a blank issue with the app version and phone filled in. */
    fun reportProblem(context: Context) = openIssue(
        context,
        "",
        "**What happened?**\n\n\n**What were you doing (live or replay, which session, how many feeds)?**\n\n\n---\n${about()}",
    )

    /** After a crash, the issue for it: the error, and a space to say what was going on. */
    fun reportCrash(context: Context, report: String) {
        val headline = report.lineSequence().drop(3).firstOrNull { it.isNotBlank() }?.substringBefore(':')?.substringAfterLast('.')
        openIssue(
            context,
            "Crash: ${headline ?: "Apexview closed unexpectedly"}",
            "**What were you doing when it closed?**\n\n\n---\n```\n${report.take(MAX_BODY - 200)}\n```",
        )
    }
}

/** After a crash: offers to report it, showing exactly what the report holds. */
@Composable
fun CrashPrompt(app: AppState) {
    val report = app.crashReport ?: return
    val context = androidx.compose.ui.platform.LocalContext.current
    Dialog(onDismissRequest = { app.closeCrashReport() }) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Ink.surface)
                .padding(24.dp)
        ) {
            Text("Apexview closed unexpectedly", style = Type.title2)
            Text(
                "You can send the error to the developer as a GitHub issue. It holds the error, the app version and your phone model, nothing else, and you'll see it before anything is sent.",
                style = Type.subhead,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
            ) {
                SecondaryButton("Not now") { app.closeCrashReport() }
                PrimaryButton("Report it") {
                    CrashReports.reportCrash(context, report)
                    app.closeCrashReport()
                }
            }
        }
    }
}
