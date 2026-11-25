package com.wififtp.server

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

object StorageAccess {
    /**
     * Android 11+ (API 30+): needs "All files access" (MANAGE_EXTERNAL_STORAGE).
     * Android 10 (API 29): classic WRITE_EXTERNAL_STORAGE + requestLegacyExternalStorage.
     */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
}
