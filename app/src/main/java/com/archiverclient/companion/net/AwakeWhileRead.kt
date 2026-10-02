package com.archiverclient.companion.net

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import java.io.FilterInputStream
import java.io.InputStream

/**
 * A response body that keeps the phone awake while it is being sent.
 *
 * NanoHTTPD writes a body from its own thread after `serve` has returned, and
 * nothing else holds the CPU up meanwhile: with the screen off, a phone can
 * sleep in the middle of a video, and the desktop's read stalls with no
 * deadline on a body to give up by (`ac-import/src/http.rs`: a body has no
 * total, because a video is as long as it is). So a wake lock goes with the
 * stream — taken when it opens, renewed while bytes move, and let go when
 * NanoHTTPD closes it, which it does after the last byte or any failure.
 *
 * Renewed rather than held outright. A desktop that stops reading — asleep, or
 * gone off the network without a word — leaves the socket blocked on a full
 * window, and a lock held for that would keep the phone awake for as long as
 * TCP takes to give up. After [STALL_MS] without a byte read, the phone may
 * sleep again.
 */
class AwakeWhileRead(
    stream: InputStream,
    private val lock: Lock,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : FilterInputStream(stream) {

    /** The part of a wake lock this needs, so a test can stand in for one. */
    interface Lock {
        /** Hold, or keep holding, for [forMs] from now. */
        fun hold(forMs: Long)
        fun release()
    }

    private var renewedAt = now().also { lock.hold(STALL_MS) }
    private var released = false

    override fun read(): Int = super.read().also { if (it >= 0) progressed() }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) progressed() }

    /** Renewing is a call into the system, so once a minute rather than once a chunk. */
    private fun progressed() {
        val at = now()
        if (at - renewedAt >= RENEW_MS) {
            lock.hold(STALL_MS)
            renewedAt = at
        }
    }

    override fun close() {
        try {
            super.close()
        } finally {
            if (!released) {
                released = true
                lock.release()
            }
        }
    }

    companion object {
        const val STALL_MS = 5 * 60 * 1000L
        const val RENEW_MS = 60 * 1000L

        /** [stream], holding a partial wake lock of its own while it is read. */
        fun of(context: Context, stream: InputStream): InputStream {
            val power = context.getSystemService(PowerManager::class.java) ?: return stream
            // Not reference counted: each hold renews the one timeout rather than
            // stacking another acquire that some release would have to match.
            val wake = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ArchiverCompanion:transfer")
                .apply { setReferenceCounted(false) }
            return AwakeWhileRead(
                stream,
                object : Lock {
                    override fun hold(forMs: Long) = wake.acquire(forMs)

                    override fun release() {
                        if (wake.isHeld) wake.release()
                    }
                },
            )
        }
    }
}
