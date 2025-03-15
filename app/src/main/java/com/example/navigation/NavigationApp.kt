package com.example.navigation

import android.app.Application
import android.util.Log

/**
 * Main application class for the Navigation app.
 * Handles app-level initialization and configuration.
 */
class NavigationApp : Application() {
    
    companion object {
        private const val TAG = "NavigationApp"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application initialized")
    }
}
