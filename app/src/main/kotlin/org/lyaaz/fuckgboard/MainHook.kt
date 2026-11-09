package org.lyaaz.fuckgboard

import android.app.Application
import android.content.Context
import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        val initialized = AtomicBoolean()
        val report: (String) -> Unit = {
            // A broken logging sink must not change the host's return values or exceptions.
            try {
                XposedBridge.log("[FuckGboard][${lpparam.processName}] $it")
            } catch (_: Throwable) {
            }
        }
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            checkNotNull(XposedBridge.hookMethod(attach, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (param.hasThrowable()) return
                    val context = param.args[0] as? Context ?: return
                    if (context.packageName != TARGET_PACKAGE || !initialized.compareAndSet(false, true)) return
                    try {
                        install(context, lpparam.classLoader, report)
                    } catch (t: Throwable) {
                        report("disabled: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            })) { "Application.attach hook rejected" }
        } catch (t: Throwable) {
            report("attach unavailable; unchanged: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun install(context: Context, loader: ClassLoader, report: (String) -> Unit) {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(TARGET_PACKAGE, 0)
        @Suppress("DEPRECATION")
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val policy = RedirectPolicy(context.filesDir, report)

        // Resolve without initializing EngineFactory, loading libraries or calling JNI ourselves.
        val manager = Class.forName(MANAGER_CLASS, false, loader)
        val enroll = manager.getDeclaredMethod("nativeEnrollDataScheme", java.lang.Long.TYPE, ByteArray::class.java)
        val withdraw = manager.getDeclaredMethod("nativeWithdrawDataScheme", java.lang.Long.TYPE, ByteArray::class.java)
        for (method in arrayOf(enroll, withdraw)) {
            check(method.returnType == java.lang.Boolean.TYPE && Modifier.isPublic(method.modifiers) &&
                Modifier.isStatic(method.modifiers) && Modifier.isNative(method.modifiers)) {
                "unsupported JNI signature: $method"
            }
        }

        val ready = AtomicBoolean()
        val bindings = SchemeBindings()
        val unrecognizedReported = AtomicBoolean()
        fun callback(enrolling: Boolean) = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!ready.get()) return
                var prepared: SchemeBindings.Call? = null
                try {
                    val handle = param.args[0] as? Long ?: return
                    if (handle == 0L) return
                    val original = param.args[1] as? ByteArray ?: return
                    var root: String? = null
                    val call = if (enrolling) {
                        val scheme = DataSchemeCodec.parse(original) ?: run {
                            if (unrecognizedReported.compareAndSet(false, true)) {
                                report("unrecognized JNI filesystem scheme; unchanged")
                            }
                            return
                        }
                        val source = policy.identifySource(File(scheme.root)) ?: return
                        root = scheme.root
                        bindings.beginEnroll(handle, original) {
                            policy.rewriteFor(source, scheme) ?: original
                        }
                    } else {
                        // The exact original bytes identify an already validated binding.
                        // Do not reparse or consult directories that may have disappeared.
                        bindings.beginWithdraw(handle, original) ?: return
                    }
                    prepared = call
                    param.setObjectExtra(CALL_STATE_KEY, Invocation(call, handle, root))
                    if (call.redirected) param.args[1] = call.bytes
                } catch (t: Throwable) {
                    prepared?.let { bindings.finish(it, false) }
                    report("JNI preparation unchanged: ${t.javaClass.simpleName}: ${t.message}")
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val invocation = param.getObjectExtra(CALL_STATE_KEY) as? Invocation ?: return
                    val success = !param.hasThrowable() && param.result == true
                    bindings.finish(invocation.call, success)
                    val operation = if (enrolling) "enroll" else "withdraw"
                    val outcome = if (param.hasThrowable()) {
                        "exception=${param.throwable.javaClass.simpleName}"
                    } else {
                        "result=${param.result}"
                    }
                    val location = invocation.root?.let { " original=$it" }.orEmpty()
                    report("JNI $operation handle=${invocation.handle}$location redirected=${invocation.call.redirected} $outcome")
                } catch (t: Throwable) {
                    report("JNI observation unavailable: ${t.javaClass.simpleName}: ${t.message}")
                }
            }
        }

        var enrollmentHook: XC_MethodHook.Unhook? = null
        var withdrawalHook: XC_MethodHook.Unhook? = null
        try {
            enrollmentHook = checkNotNull(XposedBridge.hookMethod(enroll, callback(true))) { "enrollment hook rejected" }
            withdrawalHook = checkNotNull(XposedBridge.hookMethod(withdraw, callback(false))) { "withdrawal hook rejected" }
            // An incomplete pair must never begin redirecting registrations.
            ready.set(true)
            report("installed boundary=$MANAGER_CLASS wire=filesystem-scheme-v1 overlay=per-file gboard=${info.versionName} ($versionCode) automaticLanguagePrefixes=true")
        } catch (t: Throwable) {
            ready.set(false)
            try {
                withdrawalHook?.unhook()
            } finally {
                enrollmentHook?.unhook()
            }
            throw t
        }
    }

    private class Invocation(val call: SchemeBindings.Call, val handle: Long, val root: String?)

    private companion object {
        const val TARGET_PACKAGE = "com.google.android.inputmethod.latin"
        const val MANAGER_CLASS = "com.google.android.apps.inputmethod.libs.hmm.DataManagerImpl"
        const val CALL_STATE_KEY = "org.lyaaz.fuckgboard.jniInvocation"
    }
}
