package org.lyaaz.fuckgboard

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicBoolean

class MainHook : XposedModule() {
    private lateinit var processName: String

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        processName = param.processName
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return
        val initialized = AtomicBoolean()
        val report: (String) -> Unit = {
            // A broken logging sink must not change the host's return values or exceptions.
            try {
                log(Log.INFO, "FuckGboard", "[FuckGboard][$processName] $it")
            } catch (_: Throwable) {
            }
        }
        try {
            val attach = Application::class.java.getDeclaredMethod("attach", Context::class.java)
            hook(attach).intercept(object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    // Do not catch or replace exceptions from the host.
                    val result = chain.proceed()
                    val context = chain.getArg(0) as? Context ?: return result
                    if (context.packageName != TARGET_PACKAGE || !initialized.compareAndSet(false, true)) return result
                    try {
                        install(context, param.classLoader, report)
                    } catch (t: Throwable) {
                        report("disabled: ${t.javaClass.simpleName}: ${t.message}")
                    }
                    return result
                }
            })
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
        fun callback(enrolling: Boolean) = object : Hooker {
            override fun intercept(chain: Chain): Any? {
                if (!ready.get()) return chain.proceed()
                val handle = chain.getArg(0) as? Long ?: return chain.proceed()
                if (handle == 0L) return chain.proceed()
                val original = chain.getArg(1) as? ByteArray ?: return chain.proceed()
                var root: String? = null
                val call = try {
                    if (enrolling) {
                        val scheme = DataSchemeCodec.parse(original)
                        if (scheme == null) {
                            if (unrecognizedReported.compareAndSet(false, true)) {
                                report("unrecognized JNI filesystem scheme; unchanged")
                            }
                            null
                        } else {
                            val source = policy.identifySource(File(scheme.root))
                            if (source == null) {
                                null
                            } else {
                                root = scheme.root
                                bindings.beginEnroll(handle, original) {
                                    policy.rewriteFor(source, scheme) ?: original
                                }
                            }
                        }
                    } else {
                        // Reuse the validated binding even if its directories have disappeared.
                        bindings.beginWithdraw(handle, original)
                    }
                } catch (t: Throwable) {
                    report("JNI preparation unchanged: ${t.javaClass.simpleName}: ${t.message}")
                    null
                } ?: return chain.proceed()

                var result: Any? = null
                var failure: Throwable? = null
                try {
                    result = if (call.redirected) {
                        chain.proceed(arrayOf(handle, call.bytes))
                    } else {
                        chain.proceed()
                    }
                    return result
                } catch (t: Throwable) {
                    failure = t
                    throw t
                } finally {
                    try {
                        bindings.finish(call, failure == null && result == true)
                        val operation = if (enrolling) "enroll" else "withdraw"
                        val outcome = failure?.let { "exception=${it.javaClass.simpleName}" } ?: "result=$result"
                        val location = root?.let { " original=$it" }.orEmpty()
                        report("JNI $operation handle=$handle$location redirected=${call.redirected} $outcome")
                    } catch (t: Throwable) {
                        report("JNI observation unavailable: ${t.javaClass.simpleName}: ${t.message}")
                    }
                }
            }
        }

        var enrollmentHook: HookHandle? = null
        var withdrawalHook: HookHandle? = null
        try {
            enrollmentHook = hook(enroll).intercept(callback(true))
            withdrawalHook = hook(withdraw).intercept(callback(false))
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

    private companion object {
        const val TARGET_PACKAGE = "com.google.android.inputmethod.latin"
        const val MANAGER_CLASS = "com.google.android.apps.inputmethod.libs.hmm.DataManagerImpl"
    }
}
