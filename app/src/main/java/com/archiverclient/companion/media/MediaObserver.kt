package com.archiverclient.companion.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore

/**
 * A push from MediaStore whenever the camera roll changes.
 *
 * Debounced, because a single burst shot or a download of fifty images arrives
 * as fifty callbacks in a second and each one would otherwise start its own
 * scan. Waiting a moment turns that into one.
 */
class MediaObserver(
    private val context: Context,
    private val debounceMs: Long = 5_000,
    private val onChanged: () -> Unit,
) {

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var observer: ContentObserver? = null
    private val fire = Runnable { onChanged() }

    fun start() {
        if (observer != null) return
        val t = HandlerThread("media-observer").apply { start() }
        val h = Handler(t.looper)
        val o = object : ContentObserver(h) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                h.removeCallbacks(fire)
                h.postDelayed(fire, debounceMs)
            }
        }
        // `notifyForDescendants` because a change arrives against the individual item's
        // uri, not the collection's, and we are watching the collection.
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o,
        )
        context.contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, o,
        )
        thread = t
        handler = h
        observer = o
    }

    fun stop() {
        observer?.let { context.contentResolver.unregisterContentObserver(it) }
        handler?.removeCallbacks(fire)
        thread?.quitSafely()
        observer = null
        handler = null
        thread = null
    }
}
