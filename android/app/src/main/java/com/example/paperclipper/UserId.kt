package com.example.paperclipper

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import java.util.UUID

/**
 * Stable identifier for the per-user daily quota the server enforces. Uses the signed-in Firebase
 * UID when available, otherwise a persistent per-install UUID kept in SharedPreferences.
 */
object UserId {
    fun get(context: Context): String {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (uid != null) return "uid:$uid"

        val prefs = context.getSharedPreferences("paperclipper", Context.MODE_PRIVATE)
        prefs.getString("install_id", null)?.let { return "dev:$it" }
        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString("install_id", fresh).apply()
        return "dev:$fresh"
    }
}
