package com.example.navigation

import android.util.Log
import com.here.sdk.consent.Consent
import com.here.sdk.consent.ConsentEngine
import com.here.sdk.core.Location
import com.here.sdk.core.LocationListener
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.location.LocationEngine
import com.here.sdk.location.LocationEngineStatus
import com.here.sdk.location.LocationFeature

/**
 * A reference implementation using HERE Positioning to get notified on location updates
 * from various location sources available from a device and HERE services.
 */
class HEREPositioningProvider {
    
    companion object {
        private val TAG = HEREPositioningProvider::class.java.simpleName
    }
    
    private val locationEngine: LocationEngine
    private var updateListener: LocationListener? = null
    
    private val locationStatusListener = object : com.here.sdk.location.LocationStatusListener {
        override fun onStatusChanged(locationEngineStatus: LocationEngineStatus) {
            Log.d(TAG, "Location engine status: ${locationEngineStatus.name}")
        }
        
        override fun onFeaturesNotAvailable(features: List<LocationFeature>) {
            features.forEach { feature ->
                Log.d(TAG, "Location feature not available: ${feature.name}")
            }
        }
    }
    
    init {
        val consentEngine: ConsentEngine
        
        try {
            consentEngine = ConsentEngine()
            locationEngine = LocationEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization failed: ${e.message}")
        }
        
        // Ask user to optionally opt in to HERE's data collection / improvement program
        if (consentEngine.userConsentState == Consent.UserReply.NOT_HANDLED) {
            consentEngine.requestUserConsent()
        }
    }
    
    /**
     * Get the last known device location, or null if not available
     */
    fun getLastKnownLocation(): Location? {
        return locationEngine.lastKnownLocation
    }
    
    /**
     * Start receiving location updates with the specified accuracy
     * Does nothing when engine is already running
     * 
     * @param updateListener The listener to receive location updates
     * @param accuracy The desired location accuracy level
     */
    fun startLocating(updateListener: LocationListener, accuracy: LocationAccuracy) {
        if (locationEngine.isStarted) {
            return
        }
        
        this.updateListener = updateListener
        
        // Set listeners to get location updates
        locationEngine.addLocationListener(updateListener)
        locationEngine.addLocationStatusListener(locationStatusListener)
        
        locationEngine.start(accuracy)
    }
    
    /**
     * Stop receiving location updates
     * Does nothing when engine is already stopped
     */
    fun stopLocating() {
        if (!locationEngine.isStarted) {
            return
        }
        
        // Remove listeners and stop location engine
        updateListener?.let { listener ->
            locationEngine.removeLocationListener(listener)
        }
        locationEngine.removeLocationStatusListener(locationStatusListener)
        locationEngine.stop()
    }
}
