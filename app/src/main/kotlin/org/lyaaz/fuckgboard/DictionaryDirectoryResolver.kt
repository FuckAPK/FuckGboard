package org.lyaaz.fuckgboard

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** One instance belongs to one service operation, never to the service lifetime. */
internal class DictionaryDirectoryResolver {
    private val roots = mutableMapOf<Int, DictionaryDirectoryPolicy.Certificate>()
    private var mounts: List<DictionaryDirectoryPolicy.Mount>? = null
    private var data: DictionaryDirectoryPolicy.Metadata? = null

    fun resolve(target: DictionaryTarget, packageName: String): DictionaryTarget {
        DictionaryDirectoryPolicy.validateInput(target, packageName)
        val direct = metadata(target.dataDir)
        if (direct != null) {
            DictionaryDirectoryPolicy.requirePackage(direct, target.uid)
            return target
        }

        val location = DictionaryDirectoryPolicy.mirrorLocation(target, packageName)
        val mountTable = mounts ?: readMounts().also { mounts = it }
        val trustedData = data ?: requiredMetadata("/data").also {
            DictionaryDirectoryPolicy.requireAncestor(it)
            data = it
        }
        val certificate = roots[location.user] ?: run {
            val ancestors = location.ancestors.map(::requiredMetadata)
            DictionaryDirectoryPolicy.certify(location, trustedData, ancestors, mountTable)
                .also { roots[location.user] = it }
        }
        DictionaryDirectoryPolicy.requireBacking(location, certificate)
        DictionaryDirectoryPolicy.requireLogicalBacking(location, certificate, mountTable)
        DictionaryDirectoryPolicy.requireUncoveredPackage(location, mountTable)
        DictionaryDirectoryPolicy.requirePackage(requiredMetadata(location.path), target.uid, certificate.device)
        return target.copy(dataDir = location.path)
    }

    fun securityContext(target: DictionaryTarget): String {
        DictionaryDirectoryPolicy.requirePackage(requiredMetadata(target.dataDir), target.uid)
        val context = try {
            Os.getxattr(target.dataDir, "security.selinux").toString(Charsets.UTF_8).trimEnd('\u0000')
        } catch (error: ErrnoException) {
            throw IOException("Cannot inspect CE security context", error)
        }
        if (!DictionaryCommands.validSecurityContext(context)) throw IOException("Unsafe CE security context")
        return context
    }

    private fun metadata(path: String): DictionaryDirectoryPolicy.Metadata? = try {
        val stat = Os.lstat(path)
        DictionaryDirectoryPolicy.Metadata(stat.st_mode, stat.st_uid, stat.st_gid, stat.st_dev)
    } catch (error: ErrnoException) {
        if (error.errno == OsConstants.ENOENT) null else throw IOException("Cannot inspect dictionary directory", error)
    }

    private fun requiredMetadata(path: String): DictionaryDirectoryPolicy.Metadata =
        metadata(path) ?: throw IOException("Dictionary directory is missing")

    private fun readMounts(): List<DictionaryDirectoryPolicy.Mount> =
        DictionaryDirectoryPolicy.parseMounts(File("/proc/self/mountinfo").readText())
}

/** Pure checks on kernel mount records and lstat metadata; no path probing or mutation. */
internal object DictionaryDirectoryPolicy {
    private const val DIRECTORY = 0x4000
    private const val TYPE_MASK = 0xf000
    private const val GROUP_WRITE = 0x10
    private const val WORLD_WRITE = 0x2
    private const val USER_RANGE = 100000
    private const val MIRROR = "/data_mirror/data_ce/null"
    private val packagePattern = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")

    data class Metadata(val mode: Int, val uid: Int, val gid: Int, val device: Long)
    data class Location(val user: Int, val packageName: String, val legacy: Boolean) {
        val userRoot: String get() = "$MIRROR/$user"
        val path: String get() = "$userRoot/$packageName"
        val ancestors: List<String> get() = listOf("/data_mirror", "/data_mirror/data_ce", MIRROR, userRoot)
    }
    data class Mount(
        val deviceMajor: Long,
        val deviceMinor: Long,
        val root: String,
        val point: String,
        val fileSystem: String,
    )
    data class Certificate(val device: Long, val backing: String)

    fun validateInput(target: DictionaryTarget, packageName: String) {
        ensure(target.uid >= 0 && packagePattern.matches(packageName), "Invalid dictionary identity")
        ensure(cleanPath(target.dataDir), "Invalid dictionary path")
        ensure(target.dataDir.substringAfterLast('/') == packageName, "Dictionary package mismatch")
        val user = target.uid / USER_RANGE
        if (target.dataDir.startsWith("/data/user/")) {
            ensure(target.dataDir == "/data/user/$user/$packageName", "Dictionary user mismatch")
        } else if (target.dataDir.startsWith("/data/data/")) {
            ensure(user == 0 && target.dataDir == "/data/data/$packageName", "Legacy dictionary user mismatch")
        }
    }

    fun mirrorLocation(target: DictionaryTarget, packageName: String): Location {
        validateInput(target, packageName)
        val user = target.uid / USER_RANGE
        return when (target.dataDir) {
            "/data/user/$user/$packageName" -> Location(user, packageName, false)
            "/data/data/$packageName" -> {
                ensure(user == 0, "Legacy dictionary path belongs to user zero")
                Location(user, packageName, true)
            }
            else -> throw IOException("Unsupported dictionary storage layout")
        }
    }

    fun requirePackage(metadata: Metadata, uid: Int, device: Long? = null) {
        ensure(directory(metadata) && metadata.uid == uid && metadata.gid == uid, "Unsafe dictionary directory")
        ensure(device == null || metadata.device == device, "Dictionary filesystem mismatch")
    }

    fun requireAncestor(metadata: Metadata) {
        ensure(directory(metadata) && trustedOwner(metadata.uid), "Unsafe mirror ancestor")
        ensure(metadata.mode and WORLD_WRITE == 0, "World-writable mirror ancestor")
        ensure(metadata.mode and GROUP_WRITE == 0 || trustedOwner(metadata.gid), "Untrusted writable mirror group")
    }

    fun certify(location: Location, data: Metadata, ancestors: List<Metadata>, mounts: List<Mount>): Certificate {
        requireAncestor(data)
        ensure(ancestors.size == location.ancestors.size, "Missing mirror ancestors")
        ancestors.forEach(::requireAncestor)
        ensure(ancestors.last().device == data.device, "Mirror filesystem mismatch")
        val dataMount = covering("/data", mounts)
        ensure(dataMount.root == "/" && sameDevice(dataMount, data.device), "Untrusted data mount")
        ensure(
            dataMount.fileSystem != "tmpfs" && dataMount.fileSystem != "ramfs" && dataMount.fileSystem != "rootfs",
            "Volatile data mount",
        )
        val mirrorMount = covering(location.userRoot, mounts)
        ensure(sameDevice(mirrorMount, data.device) && mirrorMount.fileSystem == dataMount.fileSystem, "Mirror mount filesystem mismatch")
        val backing = backing(location.userRoot, mirrorMount)
        return Certificate(data.device, backing).also { requireBacking(location, it) }
    }

    fun requireBacking(location: Location, certificate: Certificate) {
        val canonical = "/user/${location.user}"
        ensure(
            if (location.legacy) certificate.backing == "/data"
            else certificate.backing == canonical || location.user == 0 && certificate.backing == "/data",
            "Mirror is not the expected CE bind alias",
        )
    }

    /** Isolation may hide the logical root; a visible CE mount must nevertheless agree. */
    fun requireLogicalBacking(location: Location, certificate: Certificate, mounts: List<Mount>) {
        val logicalRoot = if (location.legacy) "/data/data" else "/data/user/${location.user}"
        val logicalMount = covering(logicalRoot, mounts)
        if (sameDevice(logicalMount, certificate.device)) {
            ensure(logicalMount.fileSystem == covering("/data", mounts).fileSystem, "Logical CE filesystem mismatch")
            ensure(backing(logicalRoot, logicalMount) == certificate.backing, "Logical and mirror CE roots differ")
        }
    }

    fun requireUncoveredPackage(location: Location, mounts: List<Mount>) {
        ensure(mounts.none { within(it.point, location.path) }, "Nested dictionary mount")
    }

    fun parseMounts(text: String): List<Mount> {
        val mounts = text.lineSequence().filter { it.isNotEmpty() }.map { line ->
            val fields = line.split(' ')
            val separator = fields.indexOf("-")
            ensure(separator >= 6 && fields.size == separator + 4, "Malformed mount record")
            ensure(fields[0].toLongOrNull() != null && fields[1].toLongOrNull() != null, "Malformed mount identity")
            val device = fields[2].split(':')
            ensure(device.size == 2, "Malformed mount device")
            val major = device[0].toLongOrNull()
            val minor = device[1].toLongOrNull()
            ensure(major != null && major >= 0 && minor != null && minor >= 0, "Malformed mount device")
            // Encoded/unusual records cannot be used to prove fixed Android CE paths.
            ensure(cleanPath(fields[3]) && cleanPath(fields[4]), "Unsupported mount path")
            Mount(major!!, minor!!, fields[3], fields[4], fields[separator + 1])
        }.toList()
        ensure(mounts.isNotEmpty(), "Missing mount records")
        return mounts
    }

    private fun backing(path: String, mount: Mount): String {
        val suffix = path.removePrefix(mount.point).trimStart('/')
        return if (suffix.isEmpty()) mount.root else
            (if (mount.root == "/") "" else mount.root) + "/" + suffix
    }

    private fun covering(path: String, mounts: List<Mount>): Mount {
        val candidates = mounts.filter { within(path, it.point) }
        val deepest = candidates.maxOfOrNull { it.point.length } ?: throw IOException("Missing covering mount")
        val selected = candidates.filter { it.point.length == deepest }
        ensure(selected.size == 1, "Ambiguous stacked mount")
        return selected.single()
    }

    private fun cleanPath(path: String): Boolean = path == "/" ||
        path.startsWith('/') && path.split('/').drop(1).all { component ->
            component.isNotEmpty() && component != "." && component != ".." &&
                component.none { it.isISOControl() || it == '\\' || it.isWhitespace() }
        }

    private fun within(path: String, parent: String): Boolean =
        path == parent || parent == "/" || path.startsWith("$parent/")

    private fun directory(metadata: Metadata): Boolean = metadata.mode and TYPE_MASK == DIRECTORY
    private fun trustedOwner(uid: Int): Boolean = uid == 0 || uid == 1000

    private fun sameDevice(mount: Mount, device: Long): Boolean {
        // Linux dev_t encoding, also used by Android's bionic st_dev.
        val major = (device ushr 8 and 0xfffL) or (device ushr 32 and 0xfffff000L)
        val minor = (device and 0xffL) or (device ushr 12 and 0xffffff00L)
        return mount.deviceMajor == major && mount.deviceMinor == minor
    }

    private fun ensure(condition: Boolean, message: String) {
        if (!condition) throw IOException(message)
    }
}
