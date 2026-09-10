package com.zzy.llmreader.web

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.http.SslError
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 用内嵌 WebView 走一遍 Cloudflare 挑战，把 cookie（`cf_clearance` 等）抓出来。
 *
 * 启动方式：`CloudflareChallengeActivity.contract`（ActivityResult contract），
 * 输入要访问的 URL，输出 `Result(cookie, userAgent)`，cookie 形如 `cf_clearance=...; cf_chl_...`。
 */
class CloudflareChallengeActivity : ComponentActivity() {

    private var webView: WebView? = null
    private var statusView: TextView? = null
    private var watcher: Job? = null
    private var finished = false
    private var targetHost: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (url.isBlank()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        targetHost = runCatching { android.net.Uri.parse(url).host.orEmpty() }
            .getOrDefault("")
        // 用 WebView 默认 UA（自带真机 Chrome 版本号），不要再硬编码"LLMReader"。
        val initialUa = intent.getStringExtra(EXTRA_USER_AGENT)
        // 调用方可指定“要看到哪个 cookie 才算成功”（默认 cf_clearance）。
        val targetCookieName = intent.getStringExtra(EXTRA_TARGET_COOKIE)
            ?.takeIf { it.isNotBlank() }
            ?: "cf_clearance"
        val titleHint = intent.getStringExtra(EXTRA_TITLE_HINT)
            ?: "加载页面中…\n需要验证或登录请完成，拿到 cookie 后会自动返回。"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        statusView = TextView(this).apply {
            text = titleHint
            setPadding(32, 32, 32, 16)
        }
        container.addView(
            statusView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            // 只在调用方明确指定 UA 时才覆盖；否则用 WebView 默认 UA（自带真实 Chrome 版本）。
            if (!initialUa.isNullOrBlank()) settings.userAgentString = initialUa
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(
                    view: WebView?,
                    startedUrl: String?,
                    favicon: android.graphics.Bitmap?,
                ) {
                    super.onPageStarted(view, startedUrl, favicon)
                    // 反指纹：抹掉 navigator.webdriver、补 plugins/languages，让 Turnstile 觉得像真人。
                    view?.evaluateJavascript(ANTI_FINGERPRINT_JS, null)
                }
                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    view?.evaluateJavascript(ANTI_FINGERPRINT_JS, null)
                    statusView?.text = "已加载：$finishedUrl"
                }
                @Suppress("OverrideDeprecatedMigration")
                override fun onReceivedSslError(
                    view: WebView?,
                    handler: SslErrorHandler?,
                    error: SslError?,
                ) {
                    handler?.cancel()
                }
            }
        }
        webView = wv
        val effectiveUa = wv.settings.userAgentString
        container.addView(
            wv,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        val doneBtn = Button(this).apply {
            text = "我已通过验证，完成"
            setOnClickListener {
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                // 只要拿到 cookie 就放过；多数情况下 cf_clearance 一定在，但站点也可能用其它名。
                if (cookies.isBlank()) {
                    statusView?.text = "WebView 还没拿到任何 cookie，请等页面真的渲染出小说目录后再点。"
                } else {
                    runCatching { CookieManager.getInstance().flush() }
                    finishWithCookie(cookies, effectiveUa)
                }
            }
        }
        container.addView(
            doneBtn,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        setContentView(container)

        wv.loadUrl(url)
        watcher = CoroutineScope(Dispatchers.Main).launch {
            // 后台轮询：拿到目标 cookie 后自动关闭；最多 240 秒。
            repeat(240) {
                delay(1000)
                val cookies = CookieManager.getInstance().getCookie(url).orEmpty()
                if (targetCookieName in cookies) {
                    delay(2000)
                    val finalCookies = CookieManager.getInstance().getCookie(url).orEmpty()
                    runCatching { CookieManager.getInstance().flush() }
                    finishWithCookie(finalCookies, effectiveUa)
                    return@launch
                }
            }
            statusView?.text = "超时仍未拿到 $targetCookieName。如果页面已完成加载/登录，可以直接点「完成」。"
        }
    }

    private fun finishWithCookie(cookies: String, ua: String) {
        if (finished) return
        finished = true
        Log.i(TAG, "captured cookies for $targetHost: ${cookies.length} chars")
        val data = Intent().apply {
            putExtra(EXTRA_COOKIE, cookies)
            putExtra(EXTRA_USER_AGENT, ua)
        }
        setResult(Activity.RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        watcher?.cancel()
        webView?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        super.onDestroy()
    }

    data class Input(
        val url: String,
        val userAgent: String = "",
        /** 看到该 cookie 名出现在当前 URL 的 cookie 串里时自动返回。默认 cf_clearance。 */
        val targetCookieName: String = "cf_clearance",
        /** 顶部提示文本。 */
        val titleHint: String = "",
    )
    data class Result(val cookie: String, val userAgent: String)

    class Contract : ActivityResultContract<Input, Result?>() {
        override fun createIntent(context: Context, input: Input): Intent =
            Intent(context, CloudflareChallengeActivity::class.java)
                .putExtra(EXTRA_URL, input.url)
                .putExtra(EXTRA_USER_AGENT, input.userAgent)
                .putExtra(EXTRA_TARGET_COOKIE, input.targetCookieName)
                .putExtra(EXTRA_TITLE_HINT, input.titleHint)

        override fun parseResult(resultCode: Int, intent: Intent?): Result? {
            if (resultCode != Activity.RESULT_OK || intent == null) return null
            val cookie = intent.getStringExtra(EXTRA_COOKIE).orEmpty()
            val ua = intent.getStringExtra(EXTRA_USER_AGENT).orEmpty()
            if (cookie.isBlank()) return null
            return Result(cookie, ua)
        }
    }

    companion object {
        private const val TAG = "CFChallenge"
        const val EXTRA_URL = "url"
        const val EXTRA_USER_AGENT = "user_agent"
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_TARGET_COOKIE = "target_cookie"
        const val EXTRA_TITLE_HINT = "title_hint"

        // 反指纹脚本：消除 WebView 自动化痕迹，让 Turnstile 觉得像普通浏览器。
        private const val ANTI_FINGERPRINT_JS = """
            (function() {
                try {
                    Object.defineProperty(navigator, 'webdriver', { get: () => false });
                } catch (e) {}
                try {
                    if (!navigator.languages || navigator.languages.length === 0) {
                        Object.defineProperty(navigator, 'languages', { get: () => ['ja-JP', 'ja', 'en-US', 'en'] });
                    }
                } catch (e) {}
                try {
                    if (!('chrome' in window)) {
                        window.chrome = { runtime: {}, app: {} };
                    }
                } catch (e) {}
                try {
                    if (navigator.permissions && navigator.permissions.query) {
                        const orig = navigator.permissions.query.bind(navigator.permissions);
                        navigator.permissions.query = function(p) {
                            if (p && p.name === 'notifications') {
                                return Promise.resolve({ state: 'prompt' });
                            }
                            return orig(p);
                        };
                    }
                } catch (e) {}
            })();
        """
    }
}
