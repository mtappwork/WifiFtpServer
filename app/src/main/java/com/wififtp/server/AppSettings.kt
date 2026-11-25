package com.wififtp.server

import android.content.Context
import android.os.Environment
import java.security.SecureRandom

/** Simple SharedPreferences wrapper for the server configuration. */
class AppSettings(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("ftp_settings", Context.MODE_PRIVATE)

    init {
        // First launch: create a random password so the server is never open by default.
        if (prefs.getString(KEY_PASSWORD, null).isNullOrEmpty()) {
            prefs.edit().putString(KEY_PASSWORD, generatePassword()).apply()
        }
    }

    var port: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var username: String
        get() = prefs.getString(KEY_USER, null) ?: "user"
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var password: String
        get() = prefs.getString(KEY_PASSWORD, null) ?: ""
        set(value) = prefs.edit().putString(KEY_PASSWORD, value).apply()

    var homePath: String
        get() = prefs.getString(KEY_HOME, null) ?: defaultHome()
        set(value) = prefs.edit().putString(KEY_HOME, value).apply()

    var readOnly: Boolean
        get() = prefs.getBoolean(KEY_READ_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_READ_ONLY, value).apply()

    companion object {
        const val DEFAULT_PORT = 2121
        private const val KEY_PORT = "port"
        private const val KEY_USER = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_HOME = "home"
        private const val KEY_READ_ONLY = "read_only"

        @Suppress("DEPRECATION")
        fun defaultHome(): String = Environment.getExternalStorageDirectory().absolutePath

        private fun generatePassword(): String {
            // No look-alike characters (0/O, 1/l/I) to make typing it on a PC easier.
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }
    }
}
