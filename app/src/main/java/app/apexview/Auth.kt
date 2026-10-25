package app.apexview

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONException
import org.json.JSONObject
import java.net.URLDecoder

/** Keeps the F1 TV subscription ("ascendon") token between launches. */
object TokenStore {
    private fun prefs(context: Context) = context.getSharedPreferences("f1mv", Context.MODE_PRIVATE)

    fun load(context: Context): String? = prefs(context).getString("ascendon", null)

    fun save(context: Context, token: String) = prefs(context).edit().putString("ascendon", token).apply()

    fun clear(context: Context) = prefs(context).edit().remove("ascendon").apply()
}

/**
 * After sign-in, account.formula1.com sets a `login-session` cookie whose JSON value holds
 * `data.subscriptionToken`. Same approach as eXViewer's auth.ts.
 */
object LoginCookie {
    const val LOGIN_URL = "https://account.formula1.com/#/en/login"

    fun readToken(): String? {
        val cookies = CookieManager.getInstance().getCookie("https://account.formula1.com") ?: return null
        val raw = cookies.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("login-session=") }
            ?.substringAfter("=")
            ?: return null

        // The value is JSON, either raw or URL-encoded.
        for (candidate in listOf(raw, URLDecoder.decode(raw, "UTF-8"))) {
            try {
                val token = JSONObject(candidate).optJSONObject("data")?.optString("subscriptionToken")
                if (!token.isNullOrEmpty()) return token
            } catch (_: JSONException) {
                // Try the next encoding.
            }
        }
        return null
    }

    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}
