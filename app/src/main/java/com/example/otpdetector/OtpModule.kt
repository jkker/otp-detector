package com.example.otpdetector

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import java.lang.reflect.Method

class OtpModule : XposedModule() {
    private var hookHandles: List<HookHandle> = emptyList()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        if (param.isSystemServer || param.processName != TARGET_PACKAGE) {
            log(Log.DEBUG, TAG, "Detaching from irrelevant process ${param.processName}")
            detach()
            return
        }

        log(Log.INFO, TAG, "Loading in ${param.processName} with libxposed API $apiVersion")
        installHooks()
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        log(Log.INFO, TAG, "Preparing hot reload")
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        log(Log.INFO, TAG, "Hot reloaded in ${param.processName}")
        hookHandles = emptyList()
        installHooks(param.oldHookHandles)
    }

    private fun installHooks(previousHooks: List<HookHandle> = emptyList()) {
        if (hookHandles.isNotEmpty()) {
            return
        }

        val oldHooksById = previousHooks.associateBy { it.id }

        hookHandles = listOf(
            installHook(
                method = NOTIFY_WITH_TAG_METHOD,
                notificationArgIndex = 2,
                hookId = HOOK_ID_NOTIFY_WITH_TAG,
                previousHook = oldHooksById[HOOK_ID_NOTIFY_WITH_TAG],
            ),
            installHook(
                method = NOTIFY_METHOD,
                notificationArgIndex = 1,
                hookId = HOOK_ID_NOTIFY,
                previousHook = oldHooksById[HOOK_ID_NOTIFY],
            ),
        )

        log(Log.INFO, TAG, "Installed ${hookHandles.size} notification hooks")
    }

    private fun installHook(
        method: Method,
        notificationArgIndex: Int,
        hookId: String,
        previousHook: HookHandle?,
    ): HookHandle {
        val interceptor: (io.github.libxposed.api.XposedInterface.Chain) -> Any? = { chain ->
            val manager = chain.thisObject as? NotificationManager
            val notification = chain.getArg(notificationArgIndex) as? Notification

            if (manager != null && notification != null) {
                processNotification(manager, notification)
            }

            chain.proceed()
        }

        return if (previousHook != null) {
            previousHook.replaceHook(interceptor)
        } else {
            hook(method)
                .setId(hookId)
                .setExceptionMode(ExceptionMode.PROTECTIVE)
                .intercept(interceptor)
        }
    }

    private fun processNotification(notificationManager: NotificationManager, notification: Notification) {
        val body = NotificationBodyExtractor.extract(notification) ?: return
        val match = OtpExtractor.extractMatch(body.text, body.bigText) ?: return

        log(Log.INFO, TAG, "Matched OTP from ${match.source.name.lowercase()}: ${match.code}")

        val context = AppContextResolver.resolve(notificationManager)
        if (context == null) {
            log(Log.WARN, TAG, "Unable to resolve application context for clipboard copy")
            return
        }

        ClipboardOtpSink.copy(context, match.code) { message ->
            log(Log.INFO, TAG, message)
        }
    }

    private companion object {
        private const val TAG = "OtpDetector"
        private const val TARGET_PACKAGE = "com.google.android.apps.googlevoice"
        private const val HOOK_ID_NOTIFY = "google-voice-notify"
        private const val HOOK_ID_NOTIFY_WITH_TAG = "google-voice-notify-with-tag"

        private val NOTIFY_METHOD: Method =
            NotificationManager::class.java.getDeclaredMethod(
                "notify",
                Int::class.javaPrimitiveType,
                Notification::class.java,
            )

        private val NOTIFY_WITH_TAG_METHOD: Method =
            NotificationManager::class.java.getDeclaredMethod(
                "notify",
                String::class.java,
                Int::class.javaPrimitiveType,
                Notification::class.java,
            )
    }
}

internal data class NotificationBody(
    val text: String,
    val bigText: String,
)

internal object NotificationBodyExtractor {
    fun extract(notification: Notification): NotificationBody? {
        val extras = notification.extras ?: return null
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()

        if (text.isBlank() && bigText.isBlank()) {
            return null
        }

        return NotificationBody(
            text = text,
            bigText = bigText.takeUnless { it.isBlank() || it == text }.orEmpty(),
        )
    }
}

internal object AppContextResolver {
    // NotificationManager does not expose a public Context, so modern Xposed modules still need a
    // tightly scoped hidden-API fallback to reach clipboard/toast services inside the hooked app.
    @SuppressLint("SoonBlockedPrivateApi", "PrivateApi", "DiscouragedPrivateApi")
    fun resolve(notificationManager: NotificationManager): Context? {
        return contextFromManager(notificationManager) ?: contextFromActivityThread()
    }

    @SuppressLint("SoonBlockedPrivateApi")
    private fun contextFromManager(notificationManager: NotificationManager): Context? =
        runCatching {
            //noinspection SoonBlockedPrivateApi
            val field = notificationManager.javaClass.getDeclaredField("mContext")
            field.isAccessible = true
            field.get(notificationManager) as? Context
        }.getOrNull()?.let { it.applicationContext ?: it }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun contextFromActivityThread(): Context? =
        runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication")
            currentApplication.isAccessible = true
            currentApplication.invoke(null) as? Context
        }.getOrNull()?.let { it.applicationContext ?: it }
}

internal object ClipboardOtpSink {
    private const val LABEL = "OTP"
    private const val DEDUPE_WINDOW_MS = 15_000L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastCopiedOtp: String? = null
    private var lastCopiedAtMs: Long = 0L

    fun copy(context: Context, otp: String, logMessage: (String) -> Unit) {
        val appContext = context.applicationContext ?: context

        mainHandler.post {
            synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                if (otp == lastCopiedOtp && now - lastCopiedAtMs < DEDUPE_WINDOW_MS) {
                    logMessage("Skipped duplicate OTP copy: $otp")
                    return@post
                }

                runCatching {
                    val clipboard =
                        appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText(LABEL, otp))
                    Toast.makeText(appContext, "OTP Copied: $otp", Toast.LENGTH_SHORT).show()
                }.onSuccess {
                    lastCopiedOtp = otp
                    lastCopiedAtMs = now
                    logMessage("Copied OTP to clipboard")
                }.onFailure { error ->
                    logMessage("Failed to copy OTP: ${error.message ?: error.javaClass.simpleName}")
                }
            }
        }
    }
}
