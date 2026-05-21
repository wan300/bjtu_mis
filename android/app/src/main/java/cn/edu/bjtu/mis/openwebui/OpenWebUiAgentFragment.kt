package cn.edu.bjtu.mis.openwebui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import com.getcapacitor.Bridge
import com.getcapacitor.CapConfig
import com.getcapacitor.CapacitorWebView
import org.json.JSONObject

class OpenWebUiAgentFragment : Fragment() {
    private var bridge: Bridge? = null
    private var currentPreferredTheme: String? = null
    private val studentName: String?
        get() = arguments
            ?.getString(ARG_STUDENT_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    private val preferredTheme: String
        get() = normalizeAgentTheme(currentPreferredTheme ?: arguments?.getString(ARG_AGENT_THEME))
    private val openWebUiBackground: String
        get() = when (preferredTheme) {
            AGENT_THEME_LIGHT -> OpenWebUiLightBackground
            else -> OpenWebUiDarkBackground
        }
    private val systemBarsStyle: String
        get() = when (preferredTheme) {
            AGENT_THEME_LIGHT -> "LIGHT"
            else -> "DARK"
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View =
        CoordinatorLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setBackgroundColor(Color.parseColor(openWebUiBackground))
            addView(
                CapacitorWebView(requireContext(), null).apply {
                    id = com.getcapacitor.android.R.id.webview
                    setBackgroundColor(Color.parseColor(openWebUiBackground))
                    addJavascriptInterface(NativeBridge(), "BjtuMisNative")
                    layoutParams = CoordinatorLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                },
            )
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        bridge = Bridge.Builder(this)
            .setInstanceState(savedInstanceState)
            .setConfig(openWebUiConfig())
            .addPlugin(NativeSsePlugin::class.java)
            .addPlugin(NativeWebSearchPlugin::class.java)
            .addPlugin(NativeSecureStorePlugin::class.java)
            .addPlugin(NativeAndroidToolsPlugin::class.java)
            .addPlugin(NativeAgentToolsPlugin::class.java)
            .addPlugin(NativeHttpPlugin::class.java)
            .create()
        disableCapacitorSystemBarInsets(view)
    }

    private fun disableCapacitorSystemBarInsets(container: View) {
        container.setPadding(0, 0, 0, 0)
        ViewCompat.setOnApplyWindowInsetsListener(container) { view, insets ->
            view.setPadding(0, 0, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(container)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        bridge?.saveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        bridge?.onStart()
    }

    override fun onResume() {
        super.onResume()
        bridge?.app?.fireStatusChange(true)
        bridge?.onResume()
    }

    override fun onPause() {
        bridge?.onPause()
        super.onPause()
    }

    override fun onStop() {
        bridge?.app?.fireStatusChange(false)
        bridge?.onStop()
        super.onStop()
    }

    override fun onDestroyView() {
        bridge?.onDestroy()
        bridge?.onDetachedFromWindow()
        bridge = null
        super.onDestroyView()
    }

    fun goBackIfPossible(): Boolean {
        val webView = bridge?.webView ?: return false
        if (!webView.canGoBack()) return false
        webView.goBack()
        return true
    }

    fun notifyHomeworkHandoffAvailable() {
        bridge?.webView?.evaluateJavascript(
            """
            window.dispatchEvent(new CustomEvent('bjtu-mis:homework-handoff'));
            """.trimIndent(),
            null,
        )
    }

    fun updatePreferredTheme(theme: String) {
        val nextTheme = normalizeAgentTheme(theme)
        if (nextTheme == preferredTheme) return

        currentPreferredTheme = nextTheme
        val backgroundColor = Color.parseColor(openWebUiBackground)
        view?.setBackgroundColor(backgroundColor)
        bridge?.webView?.apply {
            setBackgroundColor(backgroundColor)
            evaluateJavascript(
                """
                window.dispatchEvent(new CustomEvent('bjtu-mis:theme-update', {
                  detail: { theme: '$nextTheme' }
                }));
                """.trimIndent(),
                null,
            )
        }
    }

    private fun openWebUiConfig(): CapConfig =
        CapConfig.Builder(requireContext())
            .setBackgroundColor(openWebUiBackground)
            .setInitialFocus(true)
            .setPluginsConfiguration(
                JSONObject()
                    .put("CapacitorHttp", JSONObject().put("enabled", false))
                    .put(
                        "SystemBars",
                        JSONObject()
                            .put("insetsHandling", "disable")
                            .put("style", systemBarsStyle)
                            .put("hidden", false),
                    ),
            )
            .create()

    private inner class NativeBridge {
        @JavascriptInterface
        fun getStudentName(): String = studentName.orEmpty()

        @JavascriptInterface
        fun getPreferredTheme(): String = preferredTheme
    }

    companion object {
        const val AGENT_THEME_LIGHT = "light"
        const val AGENT_THEME_DARK = "dark"

        private const val ARG_STUDENT_NAME = "student_name"
        private const val ARG_AGENT_THEME = "agent_theme"
        private const val OpenWebUiLightBackground = "#FFFFFF"
        private const val OpenWebUiDarkBackground = "#171717"

        private fun normalizeAgentTheme(theme: String?): String =
            when (theme) {
                AGENT_THEME_LIGHT -> AGENT_THEME_LIGHT
                else -> AGENT_THEME_DARK
            }

        fun newInstance(studentName: String?, agentTheme: String): OpenWebUiAgentFragment =
            OpenWebUiAgentFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STUDENT_NAME, studentName)
                    putString(ARG_AGENT_THEME, normalizeAgentTheme(agentTheme))
                }
            }
    }
}
