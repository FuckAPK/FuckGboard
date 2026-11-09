package org.lyaaz.fuckgboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemeBindingsTest {
    private fun bytes(value: String) = value.toByteArray(Charsets.UTF_8)

    private fun enroll(ledger: SchemeBindings, handle: Long, original: String, selected: String): SchemeBindings.Call {
        val call = ledger.beginEnroll(handle, bytes(original)) { bytes(selected) }
        ledger.finish(call, success = true)
        return call
    }

    private fun frozenEnroll(ledger: SchemeBindings, handle: Long = 1, original: String = "original"): SchemeBindings.Call =
        ledger.beginEnroll(handle, bytes(original)) { error("Existing binding must not select again") }

    @Test fun matchesSchemeContentsRatherThanArrayIdentity() {
        val ledger = SchemeBindings()
        enroll(ledger, 1, "original", "custom")
        val repeated = frozenEnroll(ledger)
        val withdrawn = ledger.beginWithdraw(1, bytes("original"))!!
        assertTrue(repeated.redirected)
        assertArrayEquals(bytes("custom"), repeated.bytes)
        assertArrayEquals(bytes("custom"), withdrawn.bytes)
        ledger.finish(repeated, success = true)
        ledger.finish(withdrawn, success = true)
    }

    @Test fun isolatesManagerHandlesAndDifferentOriginalSchemes() {
        val ledger = SchemeBindings()
        enroll(ledger, 1, "original", "custom-one")
        enroll(ledger, 2, "original", "custom-two")
        enroll(ledger, 1, "other", "custom-other")
        assertArrayEquals(bytes("custom-one"), ledger.beginWithdraw(1, bytes("original"))!!.bytes)
        assertArrayEquals(bytes("custom-two"), ledger.beginWithdraw(2, bytes("original"))!!.bytes)
        assertArrayEquals(bytes("custom-other"), ledger.beginWithdraw(1, bytes("other"))!!.bytes)
        assertNull(ledger.beginWithdraw(3, bytes("original")))
    }

    @Test fun distinguishesContentHashCollisions() {
        val ledger = SchemeBindings()
        val first = byteArrayOf(0, 31)
        val second = byteArrayOf(1, 0)
        assertEquals(first.contentHashCode(), second.contentHashCode())
        ledger.finish(ledger.beginEnroll(1, first) { bytes("first") }, success = true)
        ledger.finish(ledger.beginEnroll(1, second) { bytes("second") }, success = true)
        assertArrayEquals(bytes("first"), ledger.beginWithdraw(1, first.copyOf())!!.bytes)
        assertArrayEquals(bytes("second"), ledger.beginWithdraw(1, second.copyOf())!!.bytes)
    }

    @Test fun freezesPassThroughDespiteNewAvailabilityAndUsesEachCallersArray() {
        val ledger = SchemeBindings()
        val original = bytes("original")
        val first = ledger.beginEnroll(1, original) { original.copyOf() }
        assertFalse(first.redirected)
        assertSame(original, first.bytes)
        ledger.finish(first, success = true)
        val anotherOriginal = original.copyOf()
        val repeated = ledger.beginEnroll(1, anotherOriginal) { error("New availability must not change an active binding") }
        assertFalse(repeated.redirected)
        assertSame(anotherOriginal, repeated.bytes)
        val withdrawOriginal = original.copyOf()
        val withdrawn = ledger.beginWithdraw(1, withdrawOriginal)!!
        assertFalse(withdrawn.redirected)
        assertSame(withdrawOriginal, withdrawn.bytes)
    }

    @Test fun absentWithdrawalDoesNotCreateABinding() {
        val ledger = SchemeBindings()
        assertNull(ledger.beginWithdraw(1, bytes("original")))
        val call = ledger.beginEnroll(1, bytes("original")) { bytes("available-now") }
        assertArrayEquals(bytes("available-now"), call.bytes)
    }

    @Test fun failedNewEnrollmentAllowsRetrySelection() {
        val ledger = SchemeBindings()
        val failed = ledger.beginEnroll(1, bytes("original")) { bytes("old") }
        ledger.finish(failed, success = false)
        assertNull(ledger.beginWithdraw(1, bytes("original")))
        val retry = ledger.beginEnroll(1, bytes("original")) { bytes("new") }
        assertArrayEquals(bytes("new"), retry.bytes)
    }

    @Test fun failedWithdrawalAndEnrollmentPreserveAnActiveChoice() {
        val ledger = SchemeBindings()
        enroll(ledger, 1, "original", "old")
        val withdrawal = ledger.beginWithdraw(1, bytes("original"))!!
        ledger.finish(withdrawal, success = false)
        val failedEnrollment = frozenEnroll(ledger)
        ledger.finish(failedEnrollment, success = false)
        assertArrayEquals(bytes("old"), ledger.beginWithdraw(1, bytes("original"))!!.bytes)
    }

    @Test fun balancedSuccessfulWithdrawalAllowsNewChoiceAndHandleReuse() {
        val ledger = SchemeBindings()
        enroll(ledger, 1, "original", "old")
        ledger.finish(ledger.beginWithdraw(1, bytes("original"))!!, success = true)
        assertNull(ledger.beginWithdraw(1, bytes("original")))
        val reused = ledger.beginEnroll(1, bytes("original")) { bytes("new") }
        assertArrayEquals(bytes("new"), reused.bytes)
    }

    @Test fun keepsChoiceUntilAllObservedSuccessfulEnrollmentsAreWithdrawn() {
        val ledger = SchemeBindings()
        enroll(ledger, 1, "original", "old")
        val second = frozenEnroll(ledger)
        ledger.finish(second, success = true)
        ledger.finish(ledger.beginWithdraw(1, bytes("original"))!!, success = true)
        val last = ledger.beginWithdraw(1, bytes("original"))!!
        assertArrayEquals(bytes("old"), last.bytes)
        ledger.finish(last, success = true)
        assertNull(ledger.beginWithdraw(1, bytes("original")))
    }

    @Test fun keepsPendingBindingForEitherEnrollmentWithdrawalCompletionOrder() {
        for (withdrawFirst in listOf(false, true)) {
            val ledger = SchemeBindings()
            val enrollment = ledger.beginEnroll(1, bytes("original")) { bytes("old") }
            val withdrawal = ledger.beginWithdraw(1, bytes("original"))!!
            val first = if (withdrawFirst) withdrawal else enrollment
            val last = if (withdrawFirst) enrollment else withdrawal
            ledger.finish(first, success = true)
            val probe = frozenEnroll(ledger)
            assertArrayEquals(bytes("old"), probe.bytes)
            ledger.finish(probe, success = false)
            ledger.finish(last, success = true)
            assertNull(ledger.beginWithdraw(1, bytes("original")))
            assertArrayEquals(bytes("new"), ledger.beginEnroll(1, bytes("original")) { bytes("new") }.bytes)
        }
    }

    @Test fun failedEnrollmentRetainsChoiceWhileAnotherOperationIsPending() {
        val ledger = SchemeBindings()
        val first = ledger.beginEnroll(1, bytes("original")) { bytes("old") }
        val second = frozenEnroll(ledger)
        ledger.finish(first, success = false)
        val third = frozenEnroll(ledger)
        assertArrayEquals(bytes("old"), third.bytes)
        ledger.finish(third, success = false)
        ledger.finish(second, success = false)
        assertArrayEquals(bytes("new"), ledger.beginEnroll(1, bytes("original")) { bytes("new") }.bytes)
    }

    @Test fun signedBalanceRetainsSuccessfulEnrollmentsAfterAnEarlyWithdrawal() {
        val ledger = SchemeBindings()
        val first = ledger.beginEnroll(1, bytes("original")) { bytes("old") }
        val second = frozenEnroll(ledger)
        val withdrawal = ledger.beginWithdraw(1, bytes("original"))!!
        ledger.finish(withdrawal, success = true)
        ledger.finish(first, success = true)
        ledger.finish(second, success = true)
        val remaining = ledger.beginWithdraw(1, bytes("original"))!!
        assertArrayEquals(bytes("old"), remaining.bytes)
        ledger.finish(remaining, success = true)
        assertNull(ledger.beginWithdraw(1, bytes("original")))
    }

    @Test fun duplicateAndForeignCompletionCannotChangeTheBalance() {
        val ledger = SchemeBindings()
        val foreign = SchemeBindings()
        val enrollment = ledger.beginEnroll(1, bytes("original")) { bytes("old") }
        foreign.finish(enrollment, success = false)
        ledger.finish(enrollment, success = true)
        ledger.finish(enrollment, success = true)
        val withdrawal = ledger.beginWithdraw(1, bytes("original"))!!
        ledger.finish(withdrawal, success = true)
        val replacement = ledger.beginEnroll(1, bytes("original")) { bytes("new") }
        ledger.finish(withdrawal, success = true)
        ledger.finish(enrollment, success = false)
        ledger.finish(replacement, success = true)
        assertArrayEquals(bytes("new"), ledger.beginWithdraw(1, bytes("original"))!!.bytes)
    }

    @Test fun snapshotsOriginalKeysAndSelectedBytesAndIsolatesEveryRedirectedCall() {
        val ledger = SchemeBindings()
        val original = bytes("original")
        val selected = bytes("custom")
        val enrollment = ledger.beginEnroll(1, original) { selected }
        assertNotSame(selected, enrollment.bytes)
        original.fill(0)
        selected.fill(0)
        enrollment.bytes.fill(0)
        ledger.finish(enrollment, success = true)
        val repeated = frozenEnroll(ledger)
        assertArrayEquals(bytes("custom"), repeated.bytes)
        repeated.bytes.fill(1)
        val withdrawal = ledger.beginWithdraw(1, bytes("original"))!!
        assertArrayEquals(bytes("custom"), withdrawal.bytes)
        assertNotSame(repeated.bytes, withdrawal.bytes)
        withdrawal.bytes.fill(2)
        ledger.finish(repeated, success = true)
        ledger.finish(withdrawal, success = false)
        assertArrayEquals(bytes("custom"), ledger.beginWithdraw(1, bytes("original"))!!.bytes)
    }

    @Test fun passThroughMutationCannotChangeTheRetainedLookupKey() {
        val ledger = SchemeBindings()
        val original = bytes("original")
        val enrollment = ledger.beginEnroll(1, original) { original }
        assertSame(original, enrollment.bytes)
        enrollment.bytes.fill(0)
        ledger.finish(enrollment, success = true)
        val withdrawalOriginal = bytes("original")
        val withdrawal = ledger.beginWithdraw(1, withdrawalOriginal)!!
        assertSame(withdrawalOriginal, withdrawal.bytes)
        assertFalse(withdrawal.redirected)
    }
}
