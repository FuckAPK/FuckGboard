package org.lyaaz.fuckgboard

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DictionaryDirectoryResolverTest {
    private val policy = DictionaryDirectoryPolicy
    private val packageName = "com.google.android.inputmethod.latin"
    private val uid = 10522
    private val device = 65088L // Linux 254:64, as used by a real Android /data mount.
    private val data = directory(1000, 1000)
    private val target = DictionaryTarget("/data/user/0/$packageName", uid)
    private val location = policy.mirrorLocation(target, packageName)
    private val ancestors = listOf(directory(0, 1000, 0x41c0, 1), directory(0, 1000, 0x41c0, 1), data, data)

    private fun directory(uid: Int, gid: Int, mode: Int = 0x41f9, device: Long = this.device) =
        DictionaryDirectoryPolicy.Metadata(mode, uid, gid, device)

    private fun records(vararg extra: String): List<DictionaryDirectoryPolicy.Mount> = policy.parseMounts(
        (listOf(
            "1 0 0:1 / / rw - rootfs rootfs rw",
            "10 1 254:64 / /data rw - f2fs /dev/block/dm-1 rw",
            "11 1 0:2 / /data_mirror rw - tmpfs tmpfs rw",
            "12 11 254:64 /user /data_mirror/data_ce/null rw shared:1 - f2fs /dev/block/dm-1 rw",
        ) + extra).joinToString("\n"),
    )

    private fun userZeroMount(root: String = "/data", point: String = location.userRoot) =
        "13 12 254:64 $root $point rw - f2fs /dev/block/dm-1 rw"

    private fun certify(
        mounts: List<DictionaryDirectoryPolicy.Mount> = records(userZeroMount()),
        metadata: List<DictionaryDirectoryPolicy.Metadata> = ancestors,
        location: DictionaryDirectoryPolicy.Location = this.location,
    ) = policy.certify(location, data, metadata, mounts)

    private fun rejects(block: () -> Unit) {
        assertThrows(IOException::class.java) { block() }
    }

    @Test fun derivesOnlyExactCanonicalAndLegacyUserZeroPaths() {
        assertEquals("/data_mirror/data_ce/null/0/$packageName", location.path)
        val legacy = policy.mirrorLocation(target.copy(dataDir = "/data/data/$packageName"), packageName)
        assertEquals(location.path, legacy.path)
        assertEquals(true, legacy.legacy)
    }

    @Test fun derivesSecondaryUserFromUidRatherThanCallerPath() {
        val secondary = policy.mirrorLocation(target.copy(dataDir = "/data/user/10/$packageName", uid = 1010522), packageName)
        assertEquals(10, secondary.user)
        assertEquals("/data_mirror/data_ce/null/10/$packageName", secondary.path)
        rejects { policy.mirrorLocation(target.copy(dataDir = "/data/user/10/$packageName"), packageName) }
        rejects { policy.mirrorLocation(target.copy(uid = 1010522), packageName) }
        rejects { policy.mirrorLocation(target.copy(dataDir = "/data/data/$packageName", uid = 1010522), packageName) }
        rejects { policy.validateInput(target.copy(dataDir = "/data/user/10/$packageName"), packageName) }
        rejects { policy.validateInput(target.copy(dataDir = "/data/data/$packageName", uid = 1010522), packageName) }
    }

    @Test fun rejectsPackageMismatchAndPrefixCollisions() {
        for (path in listOf(
            "/data/user/0/$packageName.extra", "/data/user/0/another.package",
            "/data/user/0x/$packageName", "/data/user/00/$packageName",
            "/data/user/0/$packageName/files", "/data/user/0other/$packageName",
        )) rejects { policy.mirrorLocation(target.copy(dataDir = path), packageName) }
    }

    @Test fun rejectsTraversalAndNonCanonicalPaths() {
        for (path in listOf(
            "/data/user/0/../0/$packageName", "/data/user/0/./$packageName",
            "/data/user//0/$packageName", "/data/user/0/$packageName/",
            "data/user/0/$packageName", "/data/user/0/\n$packageName",
            "/data/user/0/\\$packageName",
        )) rejects { policy.mirrorLocation(target.copy(dataDir = path), packageName) }
    }

    @Test fun rejectsUnsupportedStorageWithoutGuessing() {
        for (path in listOf(
            "/mnt/expand/uuid/user/0/$packageName", "/data/user_de/0/$packageName",
            "/storage/emulated/0/$packageName", "/data_mirror/data_ce/null/0/$packageName",
        )) rejects { policy.mirrorLocation(target.copy(dataDir = path), packageName) }
    }

    @Test fun rejectsMalformedIdentities() {
        rejects { policy.mirrorLocation(target.copy(uid = -1), packageName) }
        for (name in listOf("", "..", "a/b", "a..b", "a\\b", "a\nb")) {
            rejects { policy.mirrorLocation(target.copy(dataDir = "/data/user/0/$name"), name) }
        }
    }

    @Test fun directDirectoryRequiresExactUidAndGid() {
        policy.requirePackage(directory(uid, uid), uid)
        rejects { policy.requirePackage(directory(uid + 1, uid), uid) }
        rejects { policy.requirePackage(directory(uid, uid + 1), uid) }
        rejects { policy.requirePackage(directory(0, 0), uid) }
    }

    @Test fun directDirectoryRejectsLinksFilesAndDevices() {
        for (mode in listOf(0xa1ff, 0x81a4, 0x21b6, 0x11a4, 0x61b6)) {
            rejects { policy.requirePackage(directory(uid, uid, mode), uid) }
        }
    }

    @Test fun mirrorLeafRequiresTrustedDeviceAndExactIdentity() {
        policy.requirePackage(directory(uid, uid), uid, device)
        rejects { policy.requirePackage(directory(uid, uid, device = device + 1), uid, device) }
        rejects { policy.requirePackage(directory(uid, 1000), uid, device) }
        rejects { policy.requirePackage(directory(uid, uid, 0xa1ff), uid, device) }
    }

    @Test fun ancestorsAllowOnlyRootOrSystemOwners() {
        policy.requireAncestor(directory(0, 1000))
        policy.requireAncestor(directory(1000, 1000))
        rejects { policy.requireAncestor(directory(uid, 1000)) }
        rejects { policy.requireAncestor(directory(1001, 0)) }
    }

    @Test fun ancestorsRejectLinksAndWorldWriteEvenWithStickyBit() {
        for (mode in listOf(0xa1ff, 0x81a4, 0x41ff, 0x43ff)) {
            rejects { policy.requireAncestor(directory(0, 1000, mode)) }
        }
    }

    @Test fun groupWriteRequiresRootOrSystemGroup() {
        policy.requireAncestor(directory(0, 0, 0x41f8))
        policy.requireAncestor(directory(0, 1000, 0x41f8))
        policy.requireAncestor(directory(0, uid, 0x41e8)) // Non-writable app group is harmless.
        rejects { policy.requireAncestor(directory(0, uid, 0x41f8)) }
    }

    @Test fun certifiesActualSpecialUserZeroBind() {
        assertEquals(DictionaryDirectoryPolicy.Certificate(device, "/data"), certify())
    }

    @Test fun certifiesCanonicalUserZeroFromAncestorBindSuffix() {
        assertEquals("/user/0", certify(records()).backing)
        assertEquals("/user/0", certify(records(userZeroMount("/user/0"))).backing)
    }

    @Test fun certifiesSecondaryUserOnlyAtOwnBackingRoot() {
        val secondary = policy.mirrorLocation(target.copy(dataDir = "/data/user/10/$packageName", uid = 1010522), packageName)
        assertEquals("/user/10", certify(records(), location = secondary).backing)
        rejects { certify(records(userZeroMount("/data", secondary.userRoot)), location = secondary) }
        rejects { certify(records(userZeroMount("/user/0", secondary.userRoot)), location = secondary) }
    }

    @Test fun legacyPathRequiresSpecialDataBackingNotCanonicalUserDirectory() {
        val legacy = policy.mirrorLocation(target.copy(dataDir = "/data/data/$packageName"), packageName)
        certify(location = legacy)
        rejects { certify(records(), location = legacy) }
        rejects { policy.requireBacking(legacy, DictionaryDirectoryPolicy.Certificate(device, "/user/0")) }
    }

    @Test fun rejectsCopiedDirectoryEvenOnSameDataFilesystem() {
        rejects { certify(records(userZeroMount("/fake-copy"))) }
        rejects { certify(records(userZeroMount("/data-copy"))) }
        rejects { certify(records(userZeroMount("/user/00"))) }
    }

    @Test fun deepestMountOverridesPlausibleAncestorProof() {
        val malicious = "14 12 254:64 /elsewhere ${location.userRoot} rw - f2fs /dev/block/dm-1 rw"
        rejects { certify(records(malicious)) }
        // Unrelated prefix collisions must not alter the selected mount.
        assertEquals("/user/0", certify(records(userZeroMount("/bad", location.userRoot + "other"))).backing)
    }

    @Test fun rejectsTmpfsAndWrongDeviceEvenWhenBackingRootLooksRight() {
        rejects { certify(records("13 12 0:2 /data ${location.userRoot} rw - tmpfs tmpfs rw")) }
        rejects { certify(records("13 12 254:65 /data ${location.userRoot} rw - f2fs /dev/block/dm-2 rw")) }
        rejects { certify(records("13 12 254:64 /data ${location.userRoot} rw - ext4 /dev/block/dm-1 rw")) }
    }

    @Test fun rejectsRootOrDeviceMismatchOnTrustedDataMount() {
        val mounts = records(userZeroMount())
        val dataIndex = mounts.indexOfFirst { it.point == "/data" }
        for (changed in listOf(
            mounts[dataIndex].copy(root = "/other"),
            mounts[dataIndex].copy(deviceMinor = 65),
            mounts[dataIndex].copy(fileSystem = "tmpfs"),
        )) rejects { certify(mounts.toMutableList().apply { set(dataIndex, changed) }) }
    }

    @Test fun checksEveryMirrorAncestorAndUserRootDevice() {
        for (index in ancestors.indices) {
            rejects { certify(metadata = ancestors.toMutableList().apply { set(index, directory(uid, uid)) }) }
            rejects { certify(metadata = ancestors.toMutableList().apply { set(index, directory(0, 1000, 0xa1ff)) }) }
        }
        rejects { certify(metadata = ancestors.dropLast(1)) }
        rejects { certify(metadata = ancestors.dropLast(1) + data.copy(device = device + 1)) }
        // The mirror scaffolding may legitimately be on init's tmpfs.
        certify()
    }

    @Test fun rejectsNestedPackageAndFilesMounts() {
        val base = records(userZeroMount())
        policy.requireUncoveredPackage(location, base)
        for (point in listOf(location.path, "${location.path}/files", "${location.path}/files/fuckgboard/lang")) {
            val nested = records(userZeroMount(), userZeroMount("/unrelated", point))
            rejects { policy.requireUncoveredPackage(location, nested) }
        }
        policy.requireUncoveredPackage(location, records(userZeroMount(), userZeroMount("/other", location.path + ".other")))
    }

    @Test fun rejectsAmbiguousStackedCoveringMounts() {
        rejects { certify(records(userZeroMount(), userZeroMount())) }
    }

    @Test fun rejectsMissingKernelProof() {
        rejects { policy.parseMounts("") }
        rejects { certify(records().filterNot { it.point == "/data_mirror/data_ce/null" }) }
        rejects { certify(records().filterNot { it.point == "/data" }) }
    }

    @Test fun visibleLogicalCeMountMustMatchMirrorBacking() {
        val logical = "20 10 254:64 /data /data/user/0 rw - f2fs /dev/block/dm-1 rw"
        val mixed = records(logical)
        val certificate = certify(mixed) // A canonical /user/0 mirror alone looks legitimate.
        rejects { policy.requireLogicalBacking(location, certificate, mixed) }
        val inverse = records(userZeroMount()) // Logical /data/user/0 is canonical here.
        rejects { policy.requireLogicalBacking(location, certify(inverse), inverse) }
    }

    @Test fun acceptsMatchingCanonicalSpecialAndLegacyLogicalRoots() {
        val canonical = records()
        policy.requireLogicalBacking(location, certify(canonical), canonical)
        val special = records(
            userZeroMount(),
            "20 10 254:64 /data /data/user/0 rw - f2fs /dev/block/dm-1 rw",
        )
        policy.requireLogicalBacking(location, certify(special), special)
        val legacy = policy.mirrorLocation(target.copy(dataDir = "/data/data/$packageName"), packageName)
        policy.requireLogicalBacking(legacy, certify(special, location = legacy), special)
    }

    @Test fun hiddenLogicalRootDoesNotInvalidateCertifiedPhysicalCeAlias() {
        val hidden = records(
            userZeroMount(),
            "20 10 0:3 / /data/user/0 rw - tmpfs tmpfs rw",
        )
        policy.requireLogicalBacking(location, certify(hidden), hidden)
    }

    @Test fun rejectsEncodedUnusualOrMalformedMountRecords() {
        for (line in listOf(
            "1 0 254:64 /user /data_mirror/data_ce/null\\040hidden rw - f2fs /dev/block/dm-1 rw",
            "1 0 254:64 /user/../user /data_mirror/data_ce/null rw - f2fs /dev/block/dm-1 rw",
            "1 0 254:64 /user /data_mirror//data_ce/null rw - f2fs /dev/block/dm-1 rw",
            "1 0 bad /user /data_mirror/data_ce/null rw - f2fs /dev/block/dm-1 rw",
            "1 0 254:-1 /user /data_mirror/data_ce/null rw - f2fs /dev/block/dm-1 rw",
            "1 0 254:64 /user /data_mirror/data_ce/null rw f2fs /dev/block/dm-1 rw",
            "1 0 254:64 /user /data_mirror/data_ce/null rw - f2fs",
        )) rejects { policy.parseMounts(line) }
    }
}
