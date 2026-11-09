package org.lyaaz.fuckgboard

/** Freezes a payload until all observed successful registrations and pending calls are balanced. */
class SchemeBindings {
    private val bindings = HashMap<Key, Binding>()

    class Call internal constructor(
        val bytes: ByteArray,
        val redirected: Boolean,
        internal val owner: SchemeBindings,
        internal val binding: Binding,
        internal val enrolling: Boolean,
    ) {
        internal var finished = false
    }

    internal class Key(
        val handle: Long,
        val original: ByteArray,
        private val hash: Int = 31 * handle.hashCode() + original.contentHashCode(),
    ) {

        fun snapshot(): Key = Key(handle, original.copyOf(), hash)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean =
            other is Key && handle == other.handle && original.contentEquals(other.original)
    }

    internal class Binding(val key: Key, val selected: ByteArray?) {
        var pending = 0L
        // This is an observed call balance, not a claim about the native implementation's refcount.
        var balance = 0L
    }

    @Synchronized
    fun beginEnroll(handle: Long, original: ByteArray, select: () -> ByteArray): Call {
        val lookup = Key(handle, original)
        val binding = bindings[lookup] ?: run {
            val key = lookup.snapshot()
            val selected = select()
            val frozen = if (selected.contentEquals(key.original)) null else selected.copyOf()
            Binding(key, frozen).also { bindings[key] = it }
        }
        return reserve(binding, original, enrolling = true)
    }

    @Synchronized
    fun beginWithdraw(handle: Long, original: ByteArray): Call? {
        val binding = bindings[Key(handle, original)] ?: return null
        return reserve(binding, original, enrolling = false)
    }

    private fun reserve(binding: Binding, original: ByteArray, enrolling: Boolean): Call {
        // A pass-through must retain the caller's array; redirected calls each get an isolated copy.
        val bytes = binding.selected?.copyOf() ?: original
        val call = Call(bytes, binding.selected != null, this, binding, enrolling)
        binding.pending++
        return call
    }

    @Synchronized
    fun finish(call: Call, success: Boolean) {
        if (call.owner !== this || call.finished) return
        call.finished = true
        val binding = call.binding
        binding.pending--
        if (success) binding.balance += if (call.enrolling) 1L else -1L
        // Keep negative balances while an earlier enrollment's after-callback is still pending.
        if (binding.pending == 0L && binding.balance <= 0L) bindings.remove(binding.key)
    }
}
