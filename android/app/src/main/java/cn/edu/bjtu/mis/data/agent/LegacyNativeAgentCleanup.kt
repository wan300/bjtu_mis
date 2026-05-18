package cn.edu.bjtu.mis.data.agent

import android.content.Context
import java.io.File
import java.security.KeyStore

private const val LEGACY_AGENT_KEY_ALIAS = "bjtu_mis_agent_api_key"
private const val ANDROID_KEYSTORE = "AndroidKeyStore"

fun clearLegacyNativeAgentConfiguration(context: Context) {
    val appContext = context.applicationContext
    File(appContext.filesDir, "agent_api_key.bin").delete()

    File(appContext.filesDir, "datastore")
        .listFiles { file -> file.name.startsWith("agent_settings") }
        ?.forEach { it.delete() }

    runCatching {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (keyStore.containsAlias(LEGACY_AGENT_KEY_ALIAS)) {
            keyStore.deleteEntry(LEGACY_AGENT_KEY_ALIAS)
        }
    }
}
