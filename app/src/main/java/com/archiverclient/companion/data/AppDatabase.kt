package com.archiverclient.companion.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every version keeps its exported schema in `app/schemas`, and every step
 * between two of them is a migration: a phone that lost its pairings on an
 * update would have to be carried back to every computer to pair again.
 *
 * - 2: `peers.refusals` and `peers.refusedSince`, so one `403` no longer
 *   revokes a pairing on its own; and every video hashed again — see [To2].
 * - 3: `peers.lastHelloFrom`, so a computer is told when our address changes.
 */
@Database(
    entities = [MediaItem::class, Peer::class],
    version = 3,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2, spec = AppDatabase.To2::class),
        AutoMigration(from = 2, to = 3),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun media(): MediaDao
    abstract fun peers(): PeerDao

    /**
     * Version 2 opens videos with `setRequireOriginal` as well as images, so a
     * video is now served with the location MediaStore used to redact from it,
     * and the digest taken over the redacted copy no longer describes it. The
     * desktop throws away a download that does not match its digest, so every
     * video gives its digest up and is hashed again.
     */
    class To2 : AutoMigrationSpec {
        override fun onPostMigrate(connection: SQLiteConnection) {
            connection.execSQL("UPDATE media_items SET md5 = NULL, hashedSize = NULL WHERE isVideo = 1")
        }
    }

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun of(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "companion.db",
                ).build().also { instance = it }
            }
    }
}
