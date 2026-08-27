package cn.edu.bjtu.mis.data.thirdparty

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricPrompt
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Fixed system UI broker for plugin capabilities. Plugin code never supplies an arbitrary Intent,
 * component, URI, or permission name; it can only select one of the request shapes below.
 */
internal object PluginNativeInteractionBroker {
    sealed interface Request {
        data class OpenDocument(val mimeTypes: List<String>, val multiple: Boolean) : Request
        data class CreateDocument(val fileName: String, val mimeType: String) : Request
        data class PickMedia(val mediaType: String, val multiple: Boolean) : Request
        data class CapturePhoto(val output: File) : Request
        data class Share(val title: String?, val text: String?, val url: String?, val stream: Uri?) : Request
        data class Permissions(val permissions: Array<String>) : Request
        data class Biometric(val title: String, val subtitle: String?) : Request
    }

    data class Result(
        val uris: List<Uri> = emptyList(),
        val granted: Boolean? = null,
        val verified: Boolean? = null,
        val opened: Boolean = false,
    )

    private data class Pending(val request: Request, val result: CompletableDeferred<Result>)

    private val pending = ConcurrentHashMap<String, Pending>()

    suspend fun launch(context: android.content.Context, request: Request): Result {
        val token = UUID.randomUUID().toString()
        val result = CompletableDeferred<Result>()
        pending[token] = Pending(request, result)
        val intent = Intent(context, PluginNativeInteractionActivity::class.java)
            .putExtra(PluginNativeInteractionActivity.EXTRA_TOKEN, token)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (error: Exception) {
            pending.remove(token)
            throw PluginRuntimeException("capability_unavailable", "Unable to open Android system UI", details = null)
        }
        return try {
            result.await()
        } finally {
            pending.remove(token)
        }
    }

    internal fun request(token: String): Request? = pending[token]?.request

    internal fun complete(token: String, result: Result) {
        pending.remove(token)?.result?.complete(result)
    }

    internal fun cancel(token: String) {
        pending.remove(token)?.result?.completeExceptionally(
            PluginRuntimeException("user_cancelled", "User cancelled Android system UI"),
        )
    }
}

/** An internal activity which only dispatches fixed Android system interactions. */
class PluginNativeInteractionActivity : AppCompatActivity() {
    private var token: String? = null
    private var photoOutput: Uri? = null
    private var completed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra(EXTRA_TOKEN)
        val currentToken = token ?: return finish()
        when (val request = PluginNativeInteractionBroker.request(currentToken)) {
            is PluginNativeInteractionBroker.Request.OpenDocument -> openDocument(request)
            is PluginNativeInteractionBroker.Request.CreateDocument -> createDocument(request)
            is PluginNativeInteractionBroker.Request.PickMedia -> pickMedia(request)
            is PluginNativeInteractionBroker.Request.CapturePhoto -> capturePhoto(request)
            is PluginNativeInteractionBroker.Request.Share -> share(request)
            is PluginNativeInteractionBroker.Request.Permissions -> requestPermissions(request.permissions, REQUEST_PERMISSIONS)
            is PluginNativeInteractionBroker.Request.Biometric -> biometric(request)
            null -> finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return cancel()
        when (requestCode) {
            REQUEST_OPEN_DOCUMENT, REQUEST_PICK_MEDIA -> {
                val uris = buildList {
                    data?.clipData?.let { clip ->
                        repeat(clip.itemCount) { add(clip.getItemAt(it).uri) }
                    }
                    data?.data?.let { uri -> if (uri !in this) add(uri) }
                }
                if (uris.isEmpty()) cancel() else complete(PluginNativeInteractionBroker.Result(uris = uris))
            }
            REQUEST_CREATE_DOCUMENT -> data?.data?.let { uri ->
                complete(PluginNativeInteractionBroker.Result(uris = listOf(uri)))
            } ?: cancel()
            REQUEST_CAPTURE_PHOTO -> photoOutput?.let { uri ->
                complete(PluginNativeInteractionBroker.Result(uris = listOf(uri)))
            } ?: cancel()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS) {
            complete(
                PluginNativeInteractionBroker.Result(
                    granted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED },
                ),
            )
        }
    }

    override fun onDestroy() {
        if (!completed && isFinishing) token?.let(PluginNativeInteractionBroker::cancel)
        super.onDestroy()
    }

    private fun openDocument(request: PluginNativeInteractionBroker.Request.OpenDocument) {
        val mimeTypes = request.mimeTypes.ifEmpty { listOf("*/*") }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(if (mimeTypes.size == 1) mimeTypes.single() else "*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.multiple),
            REQUEST_OPEN_DOCUMENT,
        )
    }

    private fun createDocument(request: PluginNativeInteractionBroker.Request.CreateDocument) {
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(request.mimeType)
                .putExtra(Intent.EXTRA_TITLE, request.fileName),
            REQUEST_CREATE_DOCUMENT,
        )
    }

    private fun pickMedia(request: PluginNativeInteractionBroker.Request.PickMedia) {
        val type = when (request.mediaType) {
            "video" -> "video/*"
            "mixed" -> "*/*"
            else -> "image/*"
        }
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(type)
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, request.multiple),
            REQUEST_PICK_MEDIA,
        )
    }

    private fun capturePhoto(request: PluginNativeInteractionBroker.Request.CapturePhoto) {
        request.output.parentFile?.mkdirs()
        val output = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            request.output,
        )
        photoOutput = output
        startActivityForResult(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                .putExtra(MediaStore.EXTRA_OUTPUT, output)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION),
            REQUEST_CAPTURE_PHOTO,
        )
    }

    private fun share(request: PluginNativeInteractionBroker.Request.Share) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (request.stream == null) "text/plain" else "application/octet-stream"
            request.text?.let { putExtra(Intent.EXTRA_TEXT, it) }
            request.url?.let { putExtra(Intent.EXTRA_TEXT, listOfNotNull(request.text, it).joinToString("\n")) }
            request.stream?.let {
                putExtra(Intent.EXTRA_STREAM, it)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        startActivity(Intent.createChooser(intent, request.title))
        complete(PluginNativeInteractionBroker.Result(opened = true))
    }

    @Suppress("DEPRECATION")
    private fun biometric(request: PluginNativeInteractionBroker.Request.Biometric) {
        if (Build.VERSION.SDK_INT < 28) return cancel()
        val builder = BiometricPrompt.Builder(this)
            .setTitle(request.title)
            .setNegativeButton(getString(android.R.string.cancel), mainExecutor) { _, _ -> cancel() }
        request.subtitle?.let(builder::setSubtitle)
        val prompt = builder.build()
        prompt.authenticate(
            android.os.CancellationSignal(),
            mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    complete(PluginNativeInteractionBroker.Result(verified = true))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) = cancel()
            },
        )
    }

    private fun complete(result: PluginNativeInteractionBroker.Result) {
        if (completed) return
        completed = true
        token?.let { PluginNativeInteractionBroker.complete(it, result) }
        finish()
    }

    private fun cancel() {
        if (completed) return
        completed = true
        token?.let(PluginNativeInteractionBroker::cancel)
        finish()
    }

    companion object {
        private const val REQUEST_OPEN_DOCUMENT = 7001
        const val REQUEST_CREATE_DOCUMENT = 7002
        const val REQUEST_PICK_MEDIA = 7003
        const val REQUEST_CAPTURE_PHOTO = 7004
        const val REQUEST_PERMISSIONS = 7005
        internal const val EXTRA_TOKEN = "cn.edu.bjtu.mis.data.thirdparty.NATIVE_INTERACTION_TOKEN"
    }
}
