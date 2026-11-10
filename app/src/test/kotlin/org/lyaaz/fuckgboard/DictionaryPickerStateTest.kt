package org.lyaaz.fuckgboard

import androidx.lifecycle.SavedStateHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DictionaryPickerStateTest {
    @Test fun recreatedPickerUsesCapturedLanguageAndCanBeConsumedOnlyOnce() {
        val saved = SavedStateHandle()
        DictionaryPickerState(saved).begin("ko", DictionaryAccessMode.ROOT_SHIZUKU)

        val selection = DictionaryPickerState(saved).take(DictionaryAccessMode.ROOT_SHIZUKU)
        assertNotNull(selection)
        assertEquals("ko", selection?.language)
        assertEquals(DictionaryAccessMode.ROOT_SHIZUKU, selection?.mode)
        assertNull(DictionaryPickerState(saved).take(DictionaryAccessMode.ROOT_SHIZUKU))
    }

    @Test fun changedBackendRejectsAndClearsTheOldSelection() {
        val saved = SavedStateHandle()
        DictionaryPickerState(saved).begin("zh_TW", DictionaryAccessMode.DIRECT_ROOT)

        assertNull(DictionaryPickerState(saved).take(DictionaryAccessMode.ROOT_SHIZUKU))
        assertNull(DictionaryPickerState(saved).take(DictionaryAccessMode.DIRECT_ROOT))
    }

    @Test fun binderInvalidationSurvivesRecreationAndCannotBeReactivated() {
        val saved = SavedStateHandle()
        val original = DictionaryPickerState(saved)
        original.begin("ko", DictionaryAccessMode.ROOT_SHIZUKU)
        original.invalidate()

        val recreated = DictionaryPickerState(saved)
        assertNotNull(recreated.pending)
        assertNull(recreated.take(DictionaryAccessMode.ROOT_SHIZUKU))
        assertNull(DictionaryPickerState(saved).pending)
    }

    @Test fun cancellationClearsRestoredStateAndAllowsAnotherPicker() {
        val saved = SavedStateHandle()
        DictionaryPickerState(saved).begin("ko", DictionaryAccessMode.DIRECT_ROOT)
        DictionaryPickerState(saved).clear()

        val recreated = DictionaryPickerState(saved)
        assertNull(recreated.pending)
        recreated.begin("zh_CN", DictionaryAccessMode.DIRECT_ROOT)
        assertEquals("zh_CN", recreated.take(DictionaryAccessMode.DIRECT_ROOT)?.language)
    }

    @Test fun anotherLaunchCannotReplaceTheCapturedRequest() {
        val state = DictionaryPickerState(SavedStateHandle())
        state.begin("ko", DictionaryAccessMode.DIRECT_ROOT)
        assertThrows(IllegalStateException::class.java) {
            state.begin("zh_CN", DictionaryAccessMode.ROOT_SHIZUKU)
        }
        assertEquals("ko", state.take(DictionaryAccessMode.DIRECT_ROOT)?.language)
    }
}
