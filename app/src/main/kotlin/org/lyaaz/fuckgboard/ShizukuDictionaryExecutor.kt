package org.lyaaz.fuckgboard

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import rikka.shizuku.Shizuku

/** Owns a single, lazily bound root user service; never falls back to another backend. */
class ShizukuDictionaryExecutor(context: Context) : DictionaryExecutor {
    private val context = context.applicationContext
    private val stateLock = Any()
    private val operationLock = Any()
    private var closed = false
    private var session: Session? = null
    // Tags are fixed for this owner's lifetime and independent of obfuscated class names.
    // The user and owner suffix prevent one executor from killing another owner's service.
    private val args = Shizuku.UserServiceArgs(ComponentName(this.context, DictionaryUserService::class.java))
        .tag("${this.context.packageName}.dictionary.${Process.myUid()}.${UUID.randomUUID()}")
        .processNameSuffix("dictionary_root")
        .version(serviceVersion())
        .daemon(false)

    override fun execute(request: DictionaryRequest): DictionaryCommandResult = synchronized(operationLock) {
        checkInterrupted()
        requireRoot(context)
        val current = connect()
        val service = synchronized(stateLock) {
            if (closed) throw error(R.string.authorization_error_closed)
            current.service ?: throw error(R.string.authorization_error_shizuku_disconnected)
        }
        checkInterrupted()
        requireRoot(context)
        val reply = try {
            service.execute(
                request.action.name, request.target.dataDir, request.target.uid, request.language,
                request.name, request.source, request.modifiedMillis != null, request.modifiedMillis ?: 0L,
            ) ?: throw error(R.string.authorization_error_service)
        } catch (exception: RemoteException) {
            release(current)
            // A failed Binder reply does not prove a mutation was not performed. Never retry.
            throw error(R.string.authorization_error_shizuku_disconnected, exception)
        } catch (exception: SecurityException) {
            throw error(R.string.authorization_error_shizuku_denied, exception)
        }
        decode(reply)
    }

    private fun connect(): Session {
        val current = synchronized(stateLock) {
            if (closed) throw error(R.string.authorization_error_closed)
            session ?: Session().also { created ->
                session = created
                try {
                    // Serialize bind registration with close; close never waits for startup.
                    Shizuku.bindUserService(args, created)
                } catch (exception: RuntimeException) {
                    session = null
                    created.ready.countDown()
                    unbind(created)
                    throw error(R.string.authorization_error_service, exception)
                }
            }
        }
        val ready = try {
            current.ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            release(current)
            throw InterruptedIOException(context.getString(R.string.authorization_error_cancelled)).also {
                it.initCause(exception)
            }
        }
        if (!ready) {
            release(current)
            throw error(R.string.authorization_error_shizuku_timeout)
        }
        val connected = synchronized(stateLock) {
            if (closed) throw error(R.string.authorization_error_closed)
            session === current && current.service != null
        }
        if (!connected) {
            release(current)
            throw error(R.string.authorization_error_shizuku_disconnected)
        }
        return current
    }

    private inner class Session : ServiceConnection, IBinder.DeathRecipient {
        val ready = CountDownLatch(1)
        var service: IDictionaryUserService? = null
        private var binder: IBinder? = null

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            synchronized(stateLock) {
                if (closed || session !== this) return
                try {
                    service.linkToDeath(this, 0)
                    binder = service
                    this.service = IDictionaryUserService.Stub.asInterface(service)
                } catch (_: RemoteException) {
                    this.service = null
                } finally {
                    ready.countDown()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = binderDied()

        override fun binderDied() {
            synchronized(stateLock) {
                detach()
                ready.countDown()
            }
        }

        fun detach() {
            binder?.let {
                try {
                    it.unlinkToDeath(this, 0)
                } catch (_: NoSuchElementException) {
                    // The binder already delivered its death notification.
                }
            }
            binder = null
            service = null
        }
    }

    private fun release(current: Session) {
        synchronized(stateLock) {
            if (session !== current) return
            session = null
            current.detach()
            current.ready.countDown()
            // Complete removal before a later execute can register a fresh connection.
            unbind(current)
        }
    }

    override fun close() {
        synchronized(stateLock) {
            if (closed) return
            closed = true
            session?.let { current ->
                session = null
                current.detach()
                current.ready.countDown()
                unbind(current)
            }
        }
    }

    private fun unbind(current: Session) {
        try {
            Shizuku.unbindUserService(args, current, true)
        } catch (_: RuntimeException) {
            // A dead server already lost the service; never stop/restart Shizuku itself.
        } finally {
            try {
                // remove=true stops our service; remove=false releases the API's callback.
                Shizuku.unbindUserService(args, current, false)
            } catch (_: RuntimeException) {
                // The server may have disappeared between the two calls.
            }
        }
    }

    private fun decode(reply: Bundle): DictionaryCommandResult {
        if (!reply.containsKey(DictionaryServiceProtocol.STATUS)) throw error(R.string.authorization_error_service)
        if (reply.getInt(DictionaryServiceProtocol.STATUS) != 0) {
            val resource = when (reply.getString(DictionaryServiceProtocol.ERROR)) {
                "ROOT" -> R.string.authorization_error_shizuku_root
                "CALLER" -> R.string.authorization_error_shizuku_denied
                "REQUEST" -> R.string.authorization_error_request
                "SOURCE" -> R.string.authorization_error_source
                "GBOARD" -> R.string.dictionary_error_gboard
                "MIRROR" -> R.string.authorization_error_mirror
                else -> R.string.authorization_error_service
            }
            throw error(resource)
        }
        if (!reply.containsKey(DictionaryServiceProtocol.CODE)) throw error(R.string.authorization_error_service)
        val out = reply.getStringArrayList(DictionaryServiceProtocol.OUT) ?: throw error(R.string.authorization_error_service)
        val err = reply.getStringArrayList(DictionaryServiceProtocol.ERR) ?: throw error(R.string.authorization_error_service)
        return DictionaryCommandResult(reply.getInt(DictionaryServiceProtocol.CODE), out, err)
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedIOException(context.getString(R.string.authorization_error_cancelled))
        }
    }

    @Suppress("DEPRECATION")
    private fun serviceVersion(): Int = context.packageManager.getPackageInfo(context.packageName, 0).versionCode

    private fun error(resource: Int, cause: Throwable? = null) = IOException(context.getString(resource), cause)

    companion object {
        private const val START_TIMEOUT_SECONDS = 20L

        /** Does not request permission, bind a service, or change the selected access mode. */
        fun requireRoot(context: Context) {
            fun failure(resource: Int, cause: Throwable? = null) = IOException(context.getString(resource), cause)
            try {
                if (!Shizuku.pingBinder()) throw failure(R.string.authorization_error_shizuku_unavailable)
                if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) {
                    throw failure(R.string.authorization_error_shizuku_version)
                }
                if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                    throw failure(R.string.authorization_error_shizuku_denied)
                }
                if (Shizuku.getUid() != 0) throw failure(R.string.authorization_error_shizuku_root)
            } catch (exception: IOException) {
                throw exception
            } catch (exception: SecurityException) {
                throw failure(R.string.authorization_error_shizuku_denied, exception)
            } catch (exception: RuntimeException) {
                throw failure(R.string.authorization_error_shizuku_unavailable, exception)
            }
        }
    }
}

/** Explicit transport status is separate from the dictionary command's exit code. */
internal object DictionaryServiceProtocol {
    const val STATUS = "status"
    const val CODE = "code"
    const val OUT = "out"
    const val ERR = "err"
    const val ERROR = "error"

    fun success(code: Int, out: ArrayList<String>, err: ArrayList<String>) = Bundle().apply {
        putInt(STATUS, 0)
        putInt(CODE, code)
        putStringArrayList(OUT, out)
        putStringArrayList(ERR, err)
    }

    fun failure(kind: String) = Bundle().apply {
        putInt(STATUS, 1)
        putString(ERROR, kind)
    }
}
