package dev.aragonite.powersync.client

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Work belongs to the process and survives changes of Activity observer. */
class SingleOperation(private val worker: Executor, private val delivery: Executor) {
    fun interface Work {
        @Throws(Exception::class) fun run()
    }

    fun interface Completion {
        fun finish(failure: Exception?)
    }

    private val running = AtomicBoolean()

    fun busy(): Boolean = running.get()

    fun start(work: Work, completion: Completion): Boolean {
        if (!running.compareAndSet(false, true)) return false
        worker.execute {
            val failure =
                try {
                    work.run()
                    null
                } catch (error: Exception) {
                    error
                }
            delivery.execute {
                running.set(false)
                completion.finish(failure)
            }
        }
        return true
    }
}
