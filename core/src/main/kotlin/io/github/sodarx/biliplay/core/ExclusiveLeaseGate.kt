package io.github.sodarx.biliplay.core

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.sync.Mutex

/** A cancelled waiter never owns the resource; each acquired lease releases at most once. */
class ExclusiveLeaseGate {
    private val mutex = Mutex()

    suspend fun acquire(): Lease {
        val owner = Any()
        mutex.lock(owner)
        return Lease { mutex.unlock(owner) }
    }

    class Lease internal constructor(private val release: () -> Unit) : AutoCloseable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (closed.compareAndSet(false, true)) release()
        }
    }
}
