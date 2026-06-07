package com.example.paperclipper

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.paperclipper.data.AppDatabase
import com.example.paperclipper.data.ClippingEntity
import com.example.paperclipper.data.DbKeyManager
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented because SQLCipher's native library and the real Android Keystore don't exist on the
 * JVM. Verifies (1) the Keystore-wrapped passphrase is stable across calls, and (2) the database is
 * genuinely encrypted: it round-trips with the correct key and refuses to open with a wrong one.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDbTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun loadNative() {
        System.loadLibrary("sqlcipher")
    }

    @Test
    fun dbKeyManager_returnsStable32BytePassphrase() {
        DbKeyManager.reset(ctx)
        try {
            val first = DbKeyManager.getOrCreatePassphrase(ctx)
            val second = DbKeyManager.getOrCreatePassphrase(ctx)
            assertEquals(32, first.size)
            assertArrayEquals("passphrase must be stable so the DB reopens", first, second)
        } finally {
            DbKeyManager.reset(ctx)
        }
    }

    @Test
    fun encryptedDb_roundTripsWithCorrectKey() {
        val name = "enc-roundtrip-test.db"
        ctx.deleteDatabase(name)
        val key = "correct-key".toByteArray()

        var db = open(name, key)
        try {
            runBlocking {
                db.clippingDao().insertIfAbsent(ClippingEntity(fileName = "x.jpg", createdAt = 1L))
            }
        } finally {
            db.close()
        }

        db = open(name, key)
        try {
            val rows = runBlocking { db.clippingDao().getAll() }
            assertEquals(1, rows.size)
            assertEquals("x.jpg", rows[0].fileName)
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    @Test
    fun encryptedDb_wrongKeyCannotRead() {
        val name = "enc-wrongkey-test.db"
        ctx.deleteDatabase(name)

        var db = open(name, "right-key".toByteArray())
        try {
            runBlocking {
                db.clippingDao().insertIfAbsent(ClippingEntity(fileName = "y.jpg", createdAt = 2L))
            }
        } finally {
            db.close()
        }

        db = open(name, "wrong-key".toByteArray())
        try {
            runBlocking { db.clippingDao().getAll() }
            fail("reading with the wrong passphrase must fail")
        } catch (expected: Exception) {
            assertTrue(true)
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    private fun open(name: String, key: ByteArray): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key))
            .build()
}
