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
    private val studentName: String?
        get() = arguments
            ?.getString(ARG_STUDENT_NAME)
            ?.trim()
            ?.takeIf { it.isNotBlank() }

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
            setBackgroundColor(Color.parseColor(OpenWebUiBackground))
            addView(
                CapacitorWebView(requireContext(), null).apply {
                    id = com.getcapacitor.android.R.id.webview
                    setBackgroundColor(Color.parseColor(OpenWebUiBackground))
                    addJavascriptInterface(StudentProfileBridge(studentName), "BjtuMisNative")
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

    private fun openWebUiConfig(): CapConfig =
        CapConfig.Builder(requireContext())
            .setBackgroundColor(OpenWebUiBackground)
            .setInitialFocus(true)
            .setPluginsConfiguration(
                JSONObject()
                    .put("CapacitorHttp", JSONObject().put("enabled", false))
                    .put(
                        "SystemBars",
                        JSONObject()
                            .put("insetsHandling", "disable")
                            .put("style", "DARK")
                            .put("hidden", false),
                    ),
            )
            .create()

    private class StudentProfileBridge(
        private val studentName: String?,
    ) {
        @JavascriptInterface
        fun getStudentName(): String = studentName.orEmpty()
    }

    companion object {
        private const val ARG_STUDENT_NAME = "student_name"
        private const val OpenWebUiBackground = "#171717"

        fun newInstance(studentName: String?): OpenWebUiAgentFragment =
            OpenWebUiAgentFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_STUDENT_NAME, studentName)
                }
            }
    }
}
