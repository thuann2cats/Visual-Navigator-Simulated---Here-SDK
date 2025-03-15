package com.example.navigation

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Convenience class to request the Android permissions as defined by manifest.
 */
class PermissionsRequestor(private val activity: Activity) {
    
    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 42
    }
    
    private var resultListener: ResultListener? = null
    
    interface ResultListener {
        fun permissionsGranted()
        fun permissionsDenied()
    }
    
    fun request(resultListener: ResultListener) {
        this.resultListener = resultListener
        
        val missingPermissions = permissionsToRequest
        if (missingPermissions.isEmpty()) {
            resultListener.permissionsGranted()
        } else {
            ActivityCompat.requestPermissions(activity, missingPermissions, PERMISSIONS_REQUEST_CODE)
        }
    }
    
    @Suppress("DEPRECATION")
    private val permissionsToRequest: Array<String>
        get() {
            val permissionList = ArrayList<String>()
            try {
                val packageName = activity.packageName
                val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
                    activity.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong())
                    )
                } else {
                    activity.packageManager.getPackageInfo(
                        packageName,
                        PackageManager.GET_PERMISSIONS
                    )
                }
                
                packageInfo.requestedPermissions?.forEach { permission ->
                    if (ContextCompat.checkSelfPermission(
                            activity, permission
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        // Exclude CHANGE_NETWORK_STATE as it does not require explicit user approval on Android 6.0
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M &&
                            permission == Manifest.permission.CHANGE_NETWORK_STATE
                        ) {
                            return@forEach
                        }
                        
                        // Skip ACCESS_BACKGROUND_LOCATION for Android versions below Q (API 29)
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                            permission == Manifest.permission.ACCESS_BACKGROUND_LOCATION
                        ) {
                            return@forEach
                        }
                        
                        permissionList.add(permission)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            return permissionList.toTypedArray()
        }
    
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        val listener = resultListener ?: return
        
        if (grantResults.isEmpty()) {
            // Request was cancelled
            return
        }
        
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            
            if (allGranted) {
                listener.permissionsGranted()
            } else {
                listener.permissionsDenied()
            }
        }
    }
}
