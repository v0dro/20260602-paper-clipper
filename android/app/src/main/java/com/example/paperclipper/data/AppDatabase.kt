package com.example.paperclipper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.annotation.VisibleForTesting
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        ClippingEntity::class,
        TagEntity::class,
        ClippingTagCrossRef::class,
        CommentEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clippingDao(): ClippingDao
    abstract fun tagDao(): TagDao
    abstract fun commentDao(): CommentDao

    companion object {
        // v2 adds global tags, the clipping<->tag link table, and per-clipping comments.
        // Additive only, so existing clippings and their analysis are preserved.
        @VisibleForTesting
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `tags` " +
                        "(`id` INTEGER NOT NULL, `name` TEXT NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_tags_name` ON `tags` (`name`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `clipping_tags` " +
                        "(`fileName` TEXT NOT NULL, `tagId` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`fileName`, `tagId`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_clipping_tags_tagId` " +
                        "ON `clipping_tags` (`tagId`)",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `comments` " +
                        "(`id` INTEGER NOT NULL, `fileName` TEXT NOT NULL, `text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_comments_fileName` " +
                        "ON `comments` (`fileName`)",
                )
            }
        }

        // v3 adds the AI-generated short heading column to clippings. Additive only, nullable, so
        // existing clippings keep their analysis (older rows simply have a null heading).
        @VisibleForTesting
        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `clippings` ADD COLUMN `heading` TEXT")
            }
        }

        private const val DB_NAME = "paperclipper.db"
        private const val SECURE_PREFS = "paperclipper_secure"
        private const val FLAG_ENCRYPTED = "db_encrypted_v1"

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /**
         * Builds the encrypted Room database. The first launch after the plaintext->encrypted switch
         * deletes the old unreadable DB once (clippings are rebuilt from the images on disk by
         * [ClippingsRepository.reconcileAndProcess]); if the Keystore-wrapped passphrase can't be
         * recovered, the DB is wiped and a fresh key is minted rather than crash-looping.
         */
        private fun build(appContext: Context): AppDatabase {
            // SQLCipher's native library must be loaded before its open-helper factory is used.
            System.loadLibrary("sqlcipher")

            val prefs = appContext.getSharedPreferences(SECURE_PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(FLAG_ENCRYPTED, false)) {
                // One-time: the pre-encryption plaintext DB can't be opened by SQLCipher.
                appContext.deleteDatabase(DB_NAME)
                prefs.edit().putBoolean(FLAG_ENCRYPTED, true).apply()
            }

            val passphrase = try {
                DbKeyManager.getOrCreatePassphrase(appContext)
            } catch (e: Exception) {
                // Wrapping key lost/invalidated -> the encrypted DB is unrecoverable. Start fresh.
                appContext.deleteDatabase(DB_NAME)
                DbKeyManager.reset(appContext)
                DbKeyManager.getOrCreatePassphrase(appContext)
            }

            return Room.databaseBuilder(appContext, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }
    }
}
