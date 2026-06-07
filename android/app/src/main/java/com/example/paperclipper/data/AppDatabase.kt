package com.example.paperclipper.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ClippingEntity::class,
        TagEntity::class,
        ClippingTagCrossRef::class,
        CommentEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun clippingDao(): ClippingDao
    abstract fun tagDao(): TagDao
    abstract fun commentDao(): CommentDao

    companion object {
        // v2 adds global tags, the clipping<->tag link table, and per-clipping comments.
        // Additive only, so existing clippings and their analysis are preserved.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
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

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "paperclipper.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
