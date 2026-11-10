package org.lyaaz.fuckgboard

import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.Process
import androidx.annotation.Keep
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import kotlin.system.exitProcess

/** Runs only in this app's non-daemon Shizuku user-service process. */
@Keep
class DictionaryUserService @Keep constructor(private val context: Context) : IDictionaryUserService.Stub() {
    private val lock = Any()
    private var shell: Shell? = null

    override fun execute(
        action: String?, dataDir: String?, uid: Int, language: String?,
        name: String?, source: String?, hasModifiedMillis: Boolean, modifiedMillis: Long,
    ): Bundle {
        val caller = Binder.getCallingUid()
        return synchronized(lock) {
            try {
                checkService(caller)
                val request = try {
                    DictionaryRequest(
                        DictionaryAction.valueOf(requireNotNull(action)),
                        DictionaryTarget(requireNotNull(dataDir), uid), requireNotNull(language),
                        name, source, if (hasModifiedMillis) modifiedMillis else null,
                    )
                } catch (_: IllegalArgumentException) {
                    throw Failure("REQUEST")
                }
                val application = try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getApplicationInfo(GBOARD_PACKAGE, 0)
                } catch (_: Exception) {
                    throw Failure("GBOARD")
                }
                val target = DictionaryTarget(application.dataDir.orEmpty().trimEnd('/'), application.uid)
                try {
                    require(target.dataDir.startsWith('/') && target.uid >= 0)
                    require(!target.dataDir.contains('\u0000'))
                    DictionaryServiceValidation.validate(request, target)
                } catch (_: IllegalArgumentException) {
                    throw Failure("REQUEST")
                }
                run(request)
            } catch (failure: Failure) {
                DictionaryServiceProtocol.failure(failure.kind)
            } catch (_: Exception) {
                DictionaryServiceProtocol.failure("SERVICE")
            }
        }
    }

    private fun checkService(caller: Int) {
        if (caller != context.applicationInfo.uid) throw Failure("CALLER")
        if (Process.myUid() != 0) throw Failure("ROOT")
    }

    private fun run(request: DictionaryRequest): Bundle {
        val resolver = DictionaryDirectoryResolver()
        val target = resolve(resolver, request.target, GBOARD_PACKAGE)
        val command = if (request.action == DictionaryAction.IMPORT) {
            val ownData = File(context.applicationInfo.dataDir)
            val ownTarget = DictionaryTarget(ownData.absolutePath, context.applicationInfo.uid)
            val physicalData = File(resolve(resolver, ownTarget, context.packageName).dataDir)
            val securityContext = if (target.dataDir != request.target.dataDir) {
                try {
                    resolver.securityContext(target)
                } catch (_: IOException) {
                    throw Failure("MIRROR")
                }
            } else null
            try {
                DictionaryServiceValidation.importCommand(
                    request, ownData, ownTarget.uid, targetOverride = target, ownPhysicalData = physicalData,
                    targetContext = securityContext,
                )
            } catch (_: IllegalArgumentException) {
                throw Failure("SOURCE")
            }
        } else request.command(targetOverride = target)
        val worker = shell ?: try {
            // Root Shizuku keeps its inherited namespace; no su, nsenter or mount changes.
            Shell.Builder.create().setContext(context).setTimeout(10).build("/system/bin/sh").also {
                if (!it.isRoot) {
                    it.close()
                    throw Failure("ROOT")
                }
                shell = it
            }
        } catch (failure: Failure) {
            throw failure
        } catch (_: Exception) {
            throw Failure("SERVICE")
        }
        if (!worker.isRoot || !worker.isAlive) throw Failure("SERVICE")
        val out = ArrayList<String>()
        val err = ArrayList<String>()
        val result = worker.newJob().add(command).to(out, err).exec()
        if (err.any { it == "FG_ERROR:SOURCE" }) throw Failure("SOURCE")
        return DictionaryServiceProtocol.success(result.code, out, err)
    }

    private fun resolve(
        resolver: DictionaryDirectoryResolver, target: DictionaryTarget, packageName: String,
    ): DictionaryTarget = try {
        resolver.resolve(target, packageName)
    } catch (_: IOException) {
        throw Failure("MIRROR")
    }

    override fun destroy() {
        val caller = Binder.getCallingUid()
        // Shizuku's root server invokes the reserved destroy transaction.
        if (caller != 0 && caller != context.applicationInfo.uid) throw SecurityException("Unauthorized caller")
        synchronized(lock) {
            try {
                shell?.close()
            } finally {
                // This exits only our user-service process, never Shizuku or another app.
                exitProcess(0)
            }
        }
    }

    private class Failure(val kind: String) : Exception()

    private companion object {
        const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    }
}
