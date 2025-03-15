package com.example.navigation

import com.here.sdk.core.LocationListener
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.navigation.LocationSimulator
import com.here.sdk.navigation.LocationSimulatorOptions
import com.here.sdk.routing.Route
import com.here.time.Duration

/**
 * A class that provides simulated location updates along a given route.
 * The frequency of the provided updates can be set via LocationSimulatorOptions.
 */
class HEREPositioningSimulator {
    
    private var locationSimulator: LocationSimulator? = null
    
    /**
     * Starts route playback to generate simulated location updates
     * 
     * @param locationListener The listener to receive simulated location updates
     * @param route The route to simulate movement along
     */
    fun startLocating(locationListener: LocationListener, route: Route) {
        locationSimulator?.stop()
        
        locationSimulator = createLocationSimulator(locationListener, route)
        locationSimulator?.start()
    }
    
    /**
     * Stops the location simulation
     */
    fun stopLocating() {
        locationSimulator?.stop()
        locationSimulator = null
    }
    
    /**
     * Provides fake GPS signals based on the route geometry
     * 
     * @param locationListener The listener to receive simulated location updates
     * @param route The route to simulate movement along
     * @return A new LocationSimulator instance
     */
    private fun createLocationSimulator(locationListener: LocationListener, route: Route): LocationSimulator {
        val locationSimulatorOptions = LocationSimulatorOptions().apply {
            // Speed up the simulation by a factor of 2
            speedFactor = 2.0
            // Send location updates every 500ms
            notificationInterval = Duration.ofMillis(500)
        }
        
        val locationSimulator: LocationSimulator = try {
            LocationSimulator(route, locationSimulatorOptions)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of LocationSimulator failed: ${e.error.name}")
        }
        
        locationSimulator.setListener(locationListener)
        
        return locationSimulator
    }
}
