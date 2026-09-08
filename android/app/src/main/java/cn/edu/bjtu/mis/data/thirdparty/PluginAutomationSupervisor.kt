package cn.edu.bjtu.mis.data.thirdparty

import android.content.Context
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Keeps at most four authorized plugin runtimes alive for declared persistent Android subscriptions. */
class PluginAutomationSupervisor(
    private val context: Context,
    private val repository: ThirdPartyServiceRepository,
    private val apiRegistry: ThirdPartyServiceApiRegistry,
    private val resourceStore: ThirdPartyResourceStore,
    private val kvStore: ThirdPartyKvStore,
    private val automationStore: PluginAutomationStore,
) {
    private data class ActiveRuntime(
        val view: WebView,
        val host: PluginRuntimeHost,
        val scope: CoroutineScope,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val active = ConcurrentHashMap<PluginAutomationIdentity, ActiveRuntime>()

    init {
        scope.launch { restorePersistentRuntimes() }
    }

    fun onAccessibilityConnected() {
        scope.launch { restorePersistentRuntimes() }
    }

    fun onAccessibilityDisconnected() {
        scope.launch {
            automationStore.list()
                .filter { it.capability == "android.accessibility.events@1" }
                .map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }
                .distinct()
                .forEach { identity ->
                    val hasNativeSubscription = automationStore.list().any {
                        it.publisherSubjectId == identity.publisherSubjectId &&
                            it.serviceId == identity.serviceId &&
                            it.capability != "android.accessibility.events@1"
                    }
                    if (!hasNativeSubscription && identity !in PluginSessionKeepAlive.owners()) stopService(identity)
                }
        }
    }

    fun ensureService(publisherSubjectId: String, serviceId: String) {
        scope.launch { startService(PluginAutomationIdentity(publisherSubjectId, serviceId)) }
    }

    fun stopService(publisherSubjectId: String, serviceId: String) {
        stopService(PluginAutomationIdentity(publisherSubjectId, serviceId))
    }

    fun stopAll() {
        PluginSessionKeepAlive.stopAll()
        active.keys.toList().forEach(::stopService)
    }

    fun activeRuntimeCount(): Int = active.size

    fun releaseKeepAliveReason(publisher: String, plugin: String, force: Boolean = false) {
        scope.launch {
            val identity = PluginAutomationIdentity(publisher, plugin)
            if ((force || identity !in PluginSessionKeepAlive.owners()) && automationStore.list().none {
                it.publisherSubjectId == publisher && it.serviceId == plugin
            }) stopService(identity)
        }
    }

    private fun stopService(identity: PluginAutomationIdentity) {
        val runtime = active.remove(identity) ?: return
        scope.launch {
            withContext(Dispatchers.Main.immediate) {
                runtime.host.close()
                runtime.view.stopLoading()
                runtime.view.destroy()
                runtime.scope.cancel()
            }
        }
    }

    private suspend fun startService(identity: PluginAutomationIdentity) {
        if (active.containsKey(identity) || active.size >= MAX_ACTIVE_RUNTIMES) return
        val service = repository.getService(identity.serviceId)
        if (service == null || service.publisherSubjectId != identity.publisherSubjectId) {
            automationStore.removeService(identity.publisherSubjectId, identity.serviceId)
            return
        }
        val declared = service.manifest.requiredCapabilities + service.manifest.optionalCapabilities
        val hasKeepAlive = identity in PluginSessionKeepAlive.owners() &&
            "android.session.keepAlive@1" in service.grantedCapabilities &&
            "android.session.keepAlive@1" in declared
        val records = automationStore.list().filter {
            it.publisherSubjectId == identity.publisherSubjectId && it.serviceId == identity.serviceId
        }
        if (
            !hasKeepAlive && records.isNotEmpty() &&
                records.all { it.capability == "android.accessibility.events@1" } &&
                !AndroidAccessibilityController.isConnected()
        ) {
            return
        }
        val usable = records.filter { record ->
            record.capability in declared &&
                record.capability in service.grantedCapabilities &&
                (
                    record.capability != "android.accessibility.events@1" ||
                        AndroidAccessibilityController.isConnected()
                    )
        }
        if (!service.canRun || (usable.isEmpty() && !hasKeepAlive)) {
            automationStore.removeService(identity.publisherSubjectId, identity.serviceId)
            return
        }
        withContext(Dispatchers.Main.immediate) {
            if (active.containsKey(identity) || active.size >= MAX_ACTIVE_RUNTIMES) return@withContext
            val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val view = WebView(context)
            lateinit var host: PluginRuntimeHost
            host = PluginRuntimeHost(
                service = service,
                apiRegistry = apiRegistry,
                resourceStore = resourceStore,
                kvStore = kvStore,
                confirmer = ThirdPartySensitiveActionConfirmer { _, _ -> false },
                scope = runtimeScope,
                openExternal = {},
                onCloseService = { stopService(identity) },
                backgroundRuntime = true,
            )
            if (host.attach(view)) {
                active[identity] = ActiveRuntime(view, host, runtimeScope)
            } else {
                host.close()
                view.destroy()
                runtimeScope.cancel()
            }
        }
    }

    private suspend fun restorePersistentRuntimes() {
        automationStore.list()
            .map { PluginAutomationIdentity(it.publisherSubjectId, it.serviceId) }
            .distinct()
            .take(MAX_ACTIVE_RUNTIMES)
            .forEach { startService(it) }
    }

    private companion object {
        const val MAX_ACTIVE_RUNTIMES = 4
    }
}
