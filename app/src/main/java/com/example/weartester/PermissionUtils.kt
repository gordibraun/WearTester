package com.example.weartester

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

object PermissionUtils {
    fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        return permissions.toTypedArray()
    }

    fun missingPermissions(context: Context): Array<String> {
        return requiredPermissions()
            .filter { permission ->
                context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
            }
            .toTypedArray()
    }
}
