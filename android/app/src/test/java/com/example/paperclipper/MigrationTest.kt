package com.example.paperclipper

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.paperclipper.data.AppDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies AppDatabase.MIGRATION_1_2 is additive: it creates the tags / clipping_tags / comments
 * tables (with their indices) while leaving existing clipping rows intact. Done by hand on a v1
 * schema so it stays a JVM/Robolectric test (no instrumentation MigrationTestHelper needed).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    private fun openV1(): SupportSQLiteDatabase {
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(dbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    // The v1 schema: just the clippings table.
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `clippings` (" +
                            "`fileName` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, " +
                            "`status` TEXT NOT NULL, `extractedText` TEXT, `summary` TEXT, " +
                            "`errorMessage` TEXT, `model` TEXT, `processedAt` INTEGER, " +
                            "PRIMARY KEY(`fileName`))",
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(config).writableDatabase
    }

    private fun SupportSQLiteDatabase.exists(type: String, name: String): Boolean =
        query("SELECT name FROM sqlite_master WHERE type = ? AND name = ?", arrayOf(type, name))
            .use { it.moveToFirst() }

    @Test
    fun migration1To2_addsNewTablesAndIndicesAndPreservesData() {
        val db = openV1()
        db.execSQL(
            "INSERT INTO clippings (fileName, createdAt, status, summary) " +
                "VALUES ('a.jpg', 100, 'SUCCESS', 'hello')",
        )

        AppDatabase.MIGRATION_1_2.migrate(db)

        // New tables.
        assertTrue(db.exists("table", "tags"))
        assertTrue(db.exists("table", "clipping_tags"))
        assertTrue(db.exists("table", "comments"))
        // New indices.
        assertTrue(db.exists("index", "index_tags_name"))
        assertTrue(db.exists("index", "index_clipping_tags_tagId"))
        assertTrue(db.exists("index", "index_comments_fileName"))

        // Existing clipping row is untouched.
        db.query("SELECT summary FROM clippings WHERE fileName = 'a.jpg'").use {
            assertTrue(it.moveToFirst())
            assertEquals("hello", it.getString(0))
        }
        db.close()
    }

    @Test
    fun migration2To3_addsHeadingColumnAndPreservesData() {
        val db = openV1()
        AppDatabase.MIGRATION_1_2.migrate(db)
        db.execSQL(
            "INSERT INTO clippings (fileName, createdAt, status, summary) " +
                "VALUES ('a.jpg', 100, 'SUCCESS', 'hello')",
        )

        AppDatabase.MIGRATION_2_3.migrate(db)

        // Existing row survives; the new heading column defaults to NULL for old rows.
        db.query("SELECT summary, heading FROM clippings WHERE fileName = 'a.jpg'").use {
            assertTrue(it.moveToFirst())
            assertEquals("hello", it.getString(0))
            assertTrue(it.isNull(1))
        }
        db.close()
    }

    @Test
    fun migration3To4_fullChainAddsPendingReportsTableAndPreservesData() {
        // Run the whole 1 -> 2 -> 3 -> 4 chain over seeded v1 data, like a long-dormant install.
        val db = openV1()
        db.execSQL(
            "INSERT INTO clippings (fileName, createdAt, status, summary) " +
                "VALUES ('a.jpg', 100, 'SUCCESS', 'hello')",
        )
        AppDatabase.MIGRATION_1_2.migrate(db)
        AppDatabase.MIGRATION_2_3.migrate(db)

        AppDatabase.MIGRATION_3_4.migrate(db)

        // New (empty) queue table exists and is writable with the expected columns.
        assertTrue(db.exists("table", "pending_usage_reports"))
        db.execSQL(
            "INSERT INTO pending_usage_reports (reportId, createdAt, payloadJson) " +
                "VALUES ('r1', 42, '{}')",
        )
        db.query("SELECT COUNT(*) FROM pending_usage_reports").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }

        // The seeded clipping survived the entire chain.
        db.query("SELECT summary FROM clippings WHERE fileName = 'a.jpg'").use {
            assertTrue(it.moveToFirst())
            assertEquals("hello", it.getString(0))
        }
        db.close()
    }
}
