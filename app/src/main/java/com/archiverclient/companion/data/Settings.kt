package com.archiverclient.companion.data

import android.content.Context

/**
 * The few scalars that are not rows: which port we are serving on, how far each
 * volume's incremental scan has got, and whether the user has been asked things
 * we should only ask once.
 *
 * Plain [android.content.SharedPreferences]: none of it is secret, and the
 * server needs the port synchronously while it is starting.
 */
class Settings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /**
     * The port the HTTPS server last bound.
     *
     * Kept so a restart lands on the same one and a computer that already knows
     * where we are does not have to wait for a hello to find out. Not critical:
     * every hello carries the port, so a port that moves heals itself as soon as
     * we can announce ourselves.
     */
    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, 0)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    fun generationOf(volume: String): Long = prefs.getLong("$KEY_GENERATION$volume", -1)

    fun setGeneration(volume: String, value: Long) {
        prefs.edit().putLong("$KEY_GENERATION$volume", value).apply()
    }

    /** Forces the next index run to be a full reconcile rather than a delta. */
    fun forgetGenerations() {
        val stale = prefs.all.keys.filter { it.startsWith(KEY_GENERATION) }
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    var lastFullReconcileAt: Long
        get() = prefs.getLong(KEY_FULL_AT, 0)
        set(value) = prefs.edit().putLong(KEY_FULL_AT, value).apply()

    var askedForBatteryExemption: Boolean
        get() = prefs.getBoolean(KEY_ASKED_BATTERY, false)
        set(value) = prefs.edit().putBoolean(KEY_ASKED_BATTERY, value).apply()

    private companion object {
        const val KEY_PORT = "server_port"
        const val KEY_GENERATION = "generation:"
        const val KEY_FULL_AT = "full_reconcile_at"
        const val KEY_ASKED_BATTERY = "asked_battery"
    }
}
