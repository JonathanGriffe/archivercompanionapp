package com.archiverclient.companion.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * How much of the camera roll this app can actually see.
 *
 * Android 14 lets someone grant access to a hand-picked set of photographs
 * rather than the library. For most apps that is a courtesy; for this one it is
 * a trap, because everything keeps working — the index builds, the listing
 * serves, the desktop scans happily — and the archive quietly ends up holding
 * the four pictures that were picked in a permission dialog months ago.
 *
 * Worse, the desktop retires anything absent from a listing, so a full grant
 * that later narrows to a partial one would have it retire almost everything.
 * So this is a state the app names out loud rather than one it papers over.
 */
enum class MediaAccess {
    /** The whole library. The only state this app actually works in. */
    Full,

    /** Android 14+ "Select photos": a handful, chosen once, and no more. */
    Partial,

    None;

    val usable: Boolean get() = this == Full

    companion object {
        fun of(context: Context): MediaAccess {
            val images = granted(context, Manifest.permission.READ_MEDIA_IMAGES)
            val video = granted(context, Manifest.permission.READ_MEDIA_VIDEO)
            if (images && video) return Full

            val partial = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            return if (partial) Partial else None
        }

        /**
         * What to ask for.
         *
         * `READ_MEDIA_VISUAL_USER_SELECTED` is deliberately *not* in the request on
         * Android 14+. Including it is what turns the dialog into the photo picker
         * and makes a partial grant the likely outcome; leaving it out asks the
         * question this app actually needs answered. It stays declared in the
         * manifest so that a partial grant made elsewhere — in system settings —
         * is still something [of] can recognise and report.
         */
        fun request(): Array<String> = buildList {
            add(Manifest.permission.READ_MEDIA_IMAGES)
            add(Manifest.permission.READ_MEDIA_VIDEO)
            add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

        private fun granted(context: Context, permission: String) =
            ContextCompat.checkSelfPermission(context, permission) ==
                PackageManager.PERMISSION_GRANTED
    }
}
