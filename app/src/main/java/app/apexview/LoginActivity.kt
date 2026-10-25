package app.apexview

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity

/**
 * Shows F1's own sign-in page. Once the login-session cookie appears, saves the token and closes.
 * Use email and password: Google sign-in refuses to run inside an embedded browser.
 */
class LoginActivity : ComponentActivity() {
    private lateinit var web: WebView
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            if (!captureToken()) handler.postDelayed(this, 1000)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        // Look like plain Chrome; some sign-in pages reject the WebView marker.
        web.settings.userAgentString = web.settings.userAgentString.replace("; wv", "")
        web.webViewClient = WebViewClient()
        setContentView(web)

        web.loadUrl(LoginCookie.LOGIN_URL)
        handler.post(poll)
    }

    private fun captureToken(): Boolean {
        val token = LoginCookie.readToken() ?: return false
        TokenStore.save(this, token)
        setResult(RESULT_OK)
        finish()
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        web.destroy()
        super.onDestroy()
    }
}
