package app.apexview

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Channel(
    val title: String,
    val channelId: Int?,
    val isDefault: Boolean = false,
    /** Set for driver onboards. */
    val racingNumber: Int? = null,
    /** Onboards: three-letter code, surname, team and team color (ARGB). */
    val tla: String? = null,
    val driverName: String? = null,
    val team: String? = null,
    val teamColor: Int? = null,
)

data class VideoInfo(val title: String, val channels: List<Channel>, val session: SessionRef?)

/** A race weekend in the season calendar. */
data class WeekendInfo(
    val meetingKey: String,
    /** Championship round; null for testing. */
    val round: Int?,
    val name: String,
    val place: String,
    val startMs: Long,
    val endMs: Long,
    val image: String?,
    /** F1 TV's page for the weekend, which carries its full schedule. */
    val pageId: Int? = null,
) {
    fun isCurrent(now: Long) = now in startMs..endMs
    fun isDone(now: Long) = now > endMs
}

/** Something to watch: a session, a live event, highlights or a show. */
data class CatalogItem(
    val contentId: Long,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val durationSec: Int,
    val live: Boolean,
    /** When the session ran (epoch ms), 0 if unknown. */
    val startMs: Long,
    /** Scheduled but not started: shown grayed out, not playable yet. */
    val upcoming: Boolean = false,
)

data class WeekendContent(
    /** Practice, qualifying, sprint and race, in running order. */
    val sessions: List<CatalogItem>,
    /** Highlights, shows and press conferences. */
    val videos: List<CatalogItem>,
)

/** What contentPlay returns: the stream, and the Widevine license URL when the stream is protected. */
data class PlayInfo(val url: String, val licenseUrl: String?, val streamType: String)

/** One play session holding every feed of a content, keyed by channel ID. */
data class MultiPlay(val feeds: Map<Int, String>, val licenseUrl: String?, val streamType: String) {
    fun stream(url: String) = PlayInfo(url, licenseUrl, streamType)
}

/**
 * The F1 TV endpoints this app needs, ported from eXhumer/f1tv-api (MIT).
 * Every call goes through F1's own servers with the user's own subscription token.
 */
class F1TvApi(private val userAgent: String, private val chromeMajor: String) {
    var platform = "WEB_DASH"
    var ascendon: String? = null
        private set
    var entitlement: String? = null
        private set
    /** ANONYMOUS, REG, PRO, PREMIUM... from the location endpoint. */
    var tier = "ANONYMOUS"
        private set
    private var groupId = 0
    private var playVersion = "3.0"
    private var videoVersion = "4.0"

    private val loginStatus get() = if (ascendon == null) "A" else "R"

    private fun url(version: String, path: String) =
        "$BASE/$version/$loginStatus/$LANGUAGE/$platform/ALL/$path"

    suspend fun login(token: String) = withContext(Dispatchers.IO) {
        loadConfig()
        prepared = true

        val result = get(
            "$BASE/2.0/R/$LANGUAGE/$platform/ALL/USER/ENTITLEMENT",
            mapOf("ascendontoken" to token),
        ).getJSONObject("resultObj")

        entitlement = result.getString("entitlementToken")
        ascendon = token
        refreshLocation()
    }

    /** Config and location without signing in, so the catalog can be browsed before login. */
    suspend fun prepare() = withContext(Dispatchers.IO) {
        if (prepared) return@withContext
        loadConfig()
        refreshLocation()
        prepared = true
    }

    private var prepared = false

    private fun loadConfig() {
        get("$BASE/config").optJSONObject("apiConfig")?.let { config ->
            config.optString("playAPIVersion").trim().takeIf(API_VERSION::matches)?.let { playVersion = it }
            config.optString("videoAPIVersion").trim().takeIf(API_VERSION::matches)?.let { videoVersion = it }
        }
    }

    suspend fun liveNow(): List<CatalogItem> = withContext(Dispatchers.IO) {
        val items = get(url("1.0", "EVENTS/LIVENOW/$tier/$groupId"))
            .getJSONObject("resultObj")
            .optJSONArray("items") ?: return@withContext emptyList()

        (0 until items.length()).mapNotNull { i ->
            val meta = items.getJSONObject(i).optJSONObject("metadata") ?: return@mapNotNull null
            val id = meta.optLong("contentId", 0)
            if (id <= 0) return@mapNotNull null
            CatalogItem(
                contentId = id,
                title = text(meta, "titleBrief").ifEmpty { text(meta, "title") }.ifEmpty { "Live" },
                subtitle = meta.optJSONObject("emfAttributes")?.let { text(it, "Meeting_Name") },
                image = text(meta, "pictureUrl").ifEmpty { null },
                durationSec = 0,
                live = true,
                startMs = 0,
            )
        }
    }

    /** The season's race weekends and tests, in date order (as eXViewer asks for them). */
    suspend fun calendar(season: Int): List<WeekendInfo> = withContext(Dispatchers.IO) {
        search(
            "orderBy" to "meeting_End_Date", "sortOrder" to "asc",
            "filter_objectSubtype" to "Meeting", "filter_season" to "$season",
            "filter_orderByFom" to "Y", "maxResults" to "100",
        ).mapNotNull { container ->
            val meta = container.getJSONObject("metadata")
            val emf = meta.optJSONObject("emfAttributes") ?: return@mapNotNull null
            if (emf.optString("Diplay_In_Schedule") == "false") return@mapNotNull null
            val testing = text(emf, "IsTestEvent") == "true"
            WeekendInfo(
                meetingKey = text(emf, "MeetingKey").ifEmpty { return@mapNotNull null },
                round = text(emf, "Championship_Meeting_Ordinal").toIntOrNull()?.takeIf { !testing && it > 0 },
                name = text(emf, "Meeting_Name"),
                place = text(emf, "Circuit_Short_Name").ifEmpty { text(emf, "Meeting_Country_Name") },
                startMs = parseDate(text(emf, "Meeting_Start_Date")),
                endMs = parseDate(text(emf, "Meeting_End_Date")),
                image = text(meta, "pictureUrl").ifEmpty { null },
                pageId = emf.optInt("PageID", 0).takeIf { it > 0 },
            )
        }
    }

    /**
     * A weekend's Formula 1 sessions and videos. Reads F1 TV's own weekend page, whose schedule
     * also lists sessions that haven't started yet (the race tomorrow, say). Falls back to a
     * search, which only has what can be watched already.
     */
    suspend fun weekend(weekend: WeekendInfo): WeekendContent = withContext(Dispatchers.IO) {
        val containers = weekend.pageId?.let { pageId ->
            runCatching { pageItems(pageId) }.getOrNull()?.takeIf { it.isNotEmpty() }
        } ?: search(
            "orderBy" to "meeting_End_Date", "sortOrder" to "asc",
            "filter_MeetingKey" to weekend.meetingKey, "filter_orderByFom" to "Y", "maxResults" to "100",
        )
        val now = System.currentTimeMillis()
        val sessions = mutableListOf<CatalogItem>()
        val videos = mutableListOf<CatalogItem>()
        val seen = mutableSetOf<Long>()
        containers.forEach { container ->
            val meta = container.optJSONObject("metadata") ?: return@forEach
            if (text(meta, "contentType") == "BUNDLE" || !seen.add(meta.optLong("contentId"))) return@forEach
            val series = text(meta, "uiSeries").uppercase()
            if (series.isNotEmpty() && series != "F1") return@forEach
            val emf = meta.optJSONObject("emfAttributes")
            // Sessions sit in a live package (F1_Live); shows filed as sessions are in F1_Live_Shows.
            val isSession = emf?.optString("VideoType") == "meetingSession" &&
                packageNames(container).any { it.endsWith("_Live") }
            val subtype = text(meta, "contentSubtype")
            val start = emf?.optLong("sessionStartDate", 0) ?: 0
            val live = subtype == "LIVE"
            // A scheduled event that hasn't begun: shown, but not playable yet.
            val upcoming = subtype == "LIVE_EVENT" && start > now
            val item = CatalogItem(
                contentId = meta.optLong("contentId"),
                title = text(meta, "titleBrief").ifEmpty { text(meta, "title") }.trim(),
                subtitle = when {
                    live -> "Live"
                    upcoming -> SESSION_TIME.format(java.util.Date(start))
                    start > 0 && (isSession || subtype == "LIVE_EVENT") -> SESSION_TIME.format(java.util.Date(start))
                    else -> subtype.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
                },
                image = text(meta, "pictureUrl").ifEmpty { null },
                durationSec = meta.optInt("duration"),
                live = live,
                startMs = start,
                upcoming = upcoming,
            )
            if (isSession) sessions += item else videos += item
        }
        WeekendContent(
            sessions.sortedBy { it.startMs },
            // Shows still to come lead, in running order; the rest keep F1 TV's order.
            videos.filter { it.upcoming }.sortedBy { it.startMs } + videos.filterNot { it.upcoming },
        )
    }

    /**
     * Every item on F1 TV's weekend page: its schedule (Formula 1 events) and its rows of
     * highlights, shows and press conferences. F1 Academy and archive rows are left out.
     */
    private fun pageItems(pageId: Int): List<JSONObject> {
        val containers = get(url("2.0", "PAGE/$pageId/$tier/$groupId"))
            .getJSONObject("resultObj").optJSONArray("containers") ?: return emptyList()
        val items = mutableListOf<JSONObject>()
        for (i in 0 until containers.length()) {
            val container = containers.getJSONObject(i)
            val results = container.optJSONObject("retrieveItems")?.optJSONObject("resultObj")
                ?.optJSONArray("containers") ?: continue
            when (text(container, "layout")) {
                "interactive_schedule" -> {
                    val groups = (0 until results.length()).map { results.getJSONObject(it) }
                    val group = groups.firstOrNull { text(it, "eventName") == "FORMULA 1" }
                        ?: groups.firstOrNull { text(it, "eventName") == "ALL" } ?: continue
                    val events = group.optJSONArray("events") ?: continue
                    for (j in 0 until events.length()) items += events.getJSONObject(j)
                }
                "horizontal_thumbnail" -> {
                    val label = container.optJSONObject("metadata")?.let { text(it, "label") }.orEmpty()
                    if (label.contains("Academy", ignoreCase = true) || label.contains("Archive", ignoreCase = true)) continue
                    for (j in 0 until results.length()) items += results.getJSONObject(j)
                }
            }
        }
        return items
    }

    private fun search(vararg params: Pair<String, String>): List<JSONObject> {
        val query = params.joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }
        val containers = get(url("2.0", "PAGE/SEARCH/VOD/$tier/$groupId") + "?" + query)
            .getJSONObject("resultObj").optJSONArray("containers") ?: return emptyList()
        return (0 until containers.length()).map { containers.getJSONObject(it) }
    }

    private fun packageNames(container: JSONObject): List<String> {
        val variants = container.optJSONArray("platformVariants") ?: return emptyList()
        return (0 until variants.length()).flatMap { i ->
            val packs = variants.getJSONObject(i).optJSONArray("technicalPackages") ?: return@flatMap emptyList()
            (0 until packs.length()).map { packs.getJSONObject(it).optString("packageName") }
        }
    }

    private fun parseDate(iso: String): Long =
        runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

    suspend fun contentVideo(contentId: Long): VideoInfo = withContext(Dispatchers.IO) {
        val containers = get(url(videoVersion, "CONTENT/VIDEO/$contentId/$tier/$groupId"), entitlementHeader())
            .getJSONObject("resultObj")
            .getJSONArray("containers")
        if (containers.length() == 0) throw IOException("F1 TV has no video for $contentId")

        val meta = containers.getJSONObject(0).getJSONObject("metadata")
        val streams = meta.optJSONArray("additionalStreams")
        val channels = (0 until (streams?.length() ?: 0)).map { i ->
            val stream = streams!!.getJSONObject(i)
            val onboard = text(stream, "type") == "obc"
            Channel(
                title = channelTitle(stream),
                channelId = stream.getInt("channelId"),
                isDefault = stream.optBoolean("default"),
                racingNumber = stream.optInt("racingNumber", 0).takeIf { onboard && it > 0 },
                tla = text(stream, "title").takeIf { onboard },
                driverName = text(stream, "driverLastName").ifEmpty { null },
                team = text(stream, "teamName").ifEmpty { null },
                teamColor = text(stream, "hex").ifEmpty { null }
                    ?.let { runCatching { android.graphics.Color.parseColor(it) }.getOrNull() },
            )
        }
        val title = text(meta, "title").ifEmpty { "Content $contentId" }
        VideoInfo(title, channels, sessionRef(meta, title))
    }

    /** The keys F1's timing archive files this session under, from the video's event metadata. */
    private fun sessionRef(meta: JSONObject, title: String): SessionRef? {
        val emf = meta.optJSONObject("emfAttributes") ?: return null
        val year = text(meta, "year").toIntOrNull()
            ?: text(emf, "Meeting_Start_Date").take(4).toIntOrNull()
            ?: return null
        return SessionRef(
            year = year,
            meetingKey = text(emf, "MeetingKey").toIntOrNull(),
            sessionKey = text(emf, "MeetingSessionKey").toIntOrNull(),
            circuitKey = text(emf, "CircuitKey").toIntOrNull(),
            meetingName = text(emf, "Meeting_Name"),
            title = title,
        )
    }

    /** One feed in its own play session (`player_bm`, the web app's single player). */
    suspend fun contentPlay(contentId: Long, channelId: Int?): PlayInfo = withContext(Dispatchers.IO) {
        val result = play(contentId, "player_bm", channelId)
        PlayInfo(
            url = result.getString("url"),
            licenseUrl = text(result, "laURL").ifEmpty { null },
            streamType = text(result, "streamType"),
        )
    }

    /**
     * Every feed in one play session (`player_tm`, the web app's Premium multiview). Asked with the
     * default channel, as the web app and eXViewer do. A second PLAY request can end the first
     * session, so all tiles should come from this one call.
     */
    suspend fun multiPlay(contentId: Long, defaultChannelId: Int?): MultiPlay = withContext(Dispatchers.IO) {
        val result = play(contentId, "player_tm", defaultChannelId)
        val feeds = result.getJSONObject("tme").getJSONArray("feeds")
        MultiPlay(
            feeds = (0 until feeds.length()).associate { i ->
                val feed = feeds.getJSONObject(i)
                feed.getJSONObject("metadata").getInt("channel_id") to feed.getString("url")
            },
            licenseUrl = text(result, "laURL").ifEmpty { null },
            streamType = text(result, "streamType"),
        )
    }

    private fun play(contentId: Long, player: String, channelId: Int?): JSONObject {
        val token = ascendon ?: throw IOException("Not signed in")
        val ent = entitlement ?: throw IOException("Not signed in")

        var query = "contentId=$contentId&player=$player"
        if (channelId != null) query += "&channelId=$channelId"

        val headers = mutableMapOf("ascendontoken" to token, "entitlementtoken" to ent)
        // Only the web platforms get the web player's device header; the native apps send their own.
        if (platform.startsWith("WEB")) headers["x-f1-device-info"] = webDeviceInfo()

        return get(url(playVersion, "CONTENT/PLAY") + "?" + query, headers).getJSONObject("resultObj")
    }

    private fun refreshLocation() {
        val location = get(url("1.0", "USER/LOCATION"), entitlementHeader())
            .getJSONObject("resultObj")
            .getJSONArray("userLocation")
            .getJSONObject(0)
        tier = location.getString("entitlement")
        groupId = location.getInt("groupId")
    }

    /** Mirrors the web app's `x-f1-device-info` header (see f1tv-api's device-info.ts). */
    private fun webDeviceInfo(): String {
        val multiView = tier in listOf("OPS", "VIP", "PREMIUM")
        return listOf(
            "device=web",
            "screen=browser",
            "os=android",
            "browser=chrome",
            "browserVersion=$chromeMajor",
            "osVersion=${Build.VERSION.RELEASE}",
            "appVersion=54.2.1",
            "playerVersion=8.212.0",
            "p=$multiView",
            "tms=1",
        ).joinToString(";")
    }

    private fun channelTitle(stream: JSONObject): String {
        val lastName = text(stream, "driverLastName")
        val number = stream.optInt("racingNumber", 0)
        return if (text(stream, "type") == "obc" && lastName.isNotEmpty()) "$number $lastName"
        else text(stream, "title").ifEmpty { "Channel ${stream.optInt("channelId")}" }
    }

    private fun entitlementHeader() = entitlement?.let { mapOf("entitlementtoken" to it) } ?: emptyMap()

    private fun get(url: String, headers: Map<String, String> = emptyMap()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", userAgent)
            headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }

            val code = conn.responseCode
            val body = (if (code < 400) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code >= 400) {
                val path = URL(url).path.substringAfter("/ALL/", URL(url).path)
                throw IOException("HTTP $code on $path: ${body.take(200)}")
            }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }

    /** optString turns JSON null into "null"; this returns "" instead. */
    private fun text(json: JSONObject, key: String) = if (json.isNull(key)) "" else json.optString(key)

    companion object {
        const val BASE = "https://f1tv.formula1.com"
        const val LANGUAGE = "ENG"
        val PLATFORMS = listOf("WEB_DASH", "TABLET_DASH", "MOBILE_DASH", "WEB_HLS")
        private val API_VERSION = Regex("""\d+\.\d+""")
        private val SESSION_TIME = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())

        /** F1's image resizer, as the web app and eXViewer call it. */
        fun picture(path: String?, width: Int, height: Int): String? =
            path?.let { "$BASE/image-resizer/image/$it?w=$width&h=$height&q=HI&o=L" }
    }
}
