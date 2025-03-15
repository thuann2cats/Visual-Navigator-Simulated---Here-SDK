package com.example.navigation

import android.content.Context
import android.util.Log
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.Location
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.location.LocationAccuracy
import com.here.sdk.mapview.MapView
import com.here.sdk.navigation.DynamicCameraBehavior
import com.here.sdk.navigation.SpeedBasedCameraBehavior
import com.here.sdk.navigation.VisualNavigator
import com.here.sdk.prefetcher.RoutePrefetcher
import com.here.sdk.routing.Route
import com.here.sdk.routing.RoutingError
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngineOptions
import com.here.sdk.trafficawarenavigation.DynamicRoutingListener
import com.here.time.Duration

/**
 * Shows how to start and stop turn-by-turn navigation on a car route.
 * By default, tracking mode is enabled. When navigation is stopped, tracking mode is enabled again.
 * The preferred device language determines the language for voice notifications used for TTS.
 * (Make sure to set language + region in device settings.)
 */
class NavigationExample(
    private val context: Context,
    private val mapView: MapView,
    private val messageView: App.MessageDisplay  // Changed to use our interface instead of TextView
) {
    
    companion object {
        private val TAG = NavigationExample::class.java.simpleName
    }
    
    // VisualNavigator handles the rendering of the navigation UI components
    private val visualNavigator: VisualNavigator
    
    // Two positioning providers: one for real GPS locations, one for simulated locations
    private val herePositioningProvider = HEREPositioningProvider()
    private val herePositioningSimulator = HEREPositioningSimulator()
    
    // DynamicRoutingEngine that searches for better routes based on traffic conditions
    private val dynamicRoutingEngine: DynamicRoutingEngine
    
    // RoutePrefetcher downloads map data in advance for smooth navigation
    private val routePrefetcher: RoutePrefetcher
    
    // NavigationEventHandler processes various navigation events
    private val navigationEventHandler: NavigationEventHandler
    
    init {
        // Initialize the positioning providers, navigator, and other components
        try {
            // Without a route set, this starts in tracking mode
            visualNavigator = VisualNavigator()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of VisualNavigator failed: ${e.error.name}")
        }
        
        // The RoutePrefetcher downloads map data in advance into the map cache
        // This is not mandatory, but can help to improve the guidance experience
        routePrefetcher = RoutePrefetcher(SDKNativeEngine.getSharedInstance()!!)
        
        // This enables a navigation view including a rendered navigation arrow
        visualNavigator.startRendering(mapView)
        
        // Create the dynamic routing engine to search for better routes during navigation
        dynamicRoutingEngine = createDynamicRoutingEngine()
        
        // Initialize the event handler that will process various navigation events
        navigationEventHandler = NavigationEventHandler(context, messageView)
        navigationEventHandler.setupListeners(visualNavigator, dynamicRoutingEngine)
        
        messageView.setText("Initialization completed.")
    }
    
    /**
     * Start receiving location updates from HERE Positioning
     */
    fun startLocationProvider() {
        // Set navigator as listener to receive locations from HERE Positioning
        // and choose a suitable accuracy for the tbt navigation use case
        herePositioningProvider.startLocating(visualNavigator, LocationAccuracy.NAVIGATION)
    }
    
    /**
     * Prefetch map data around the provided location and along the current route
     * 
     * @param currentGeoCoordinates The coordinates around which to prefetch data
     */
    private fun prefetchMapData(currentGeoCoordinates: GeoCoordinates) {
        // Prefetches map data around the provided location with a radius of 2 km into the map cache
        // For the best experience, this should be called as early as possible
        val radiusInMeters = 2000.0
        routePrefetcher.prefetchAroundLocationWithRadius(currentGeoCoordinates, radiusInMeters)
        
        // Prefetches map data within a corridor along the route that is currently set to the provided Navigator instance
        // This happens continuously in discrete intervals
        // If no route is set, no data will be prefetched
        routePrefetcher.prefetchAroundRouteOnIntervals(visualNavigator)
    }
    
    /**
     * Create a dynamic routing engine to periodically search for better routes during guidance
     * when the traffic situation changes
     * 
     * Note: This initiates periodic calls to the HERE Routing backend. Depending on your contract,
     * each call may be charged separately.
     * 
     * @return A new DynamicRoutingEngine instance
     */
    private fun createDynamicRoutingEngine(): DynamicRoutingEngine {
        val dynamicRoutingOptions = DynamicRoutingEngineOptions().apply {
            // Both minTimeDifference and minTimeDifferencePercentage will be checked:
            // When the poll interval is reached, the smaller difference will win
            minTimeDifference = Duration.ofSeconds(1)
            minTimeDifferencePercentage = 0.1
            
            // Below, we use 10 minutes. A common range is between 5 and 15 minutes
            pollInterval = Duration.ofMinutes(10)
        }
        
        try {
            // With the dynamic routing engine you can poll the HERE backend services to search for routes with less traffic
            // This can happen during guidance - or you can periodically update a route that is shown in a route planner
            //
            // Make sure to call dynamicRoutingEngine.updateCurrentLocation(...) to trigger execution. If this is not called,
            // no events will be delivered even if the next poll interval has been reached
            return DynamicRoutingEngine(dynamicRoutingOptions)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of DynamicRoutingEngine failed: ${e.error.name}")
        }
    }
    
    /**
     * Start navigation along a calculated route
     * 
     * @param route The route to navigate
     * @param isSimulated If true, use simulated locations; otherwise use device GPS
     * @param isCameraTrackingEnabled If true, enable camera tracking
     */
    fun startNavigation(route: Route, isSimulated: Boolean, isCameraTrackingEnabled: Boolean) {
        val startGeoCoordinates = route.geometry.vertices[0]
        prefetchMapData(startGeoCoordinates)
        
        // Switches to navigation mode when no route was set before, otherwise navigation mode is kept
        visualNavigator.setRoute(route)
        
        // Enable auto-zoom during guidance
        visualNavigator.setCameraBehavior(DynamicCameraBehavior())
        
        if (isSimulated) {
            enableRoutePlayback(route)
            messageView.setText("Starting simulated navigation.")
        } else {
            enableDevicePositioning()
            messageView.setText("Starting navigation.")
        }
        
        startDynamicSearchForBetterRoutes(route)
        
        // Synchronize with the toggle button state
        updateCameraTracking(isCameraTrackingEnabled)
    }
    
    /**
     * Start dynamic search for better routes as traffic conditions change
     * 
     * @param route The current route to improve upon
     */
    private fun startDynamicSearchForBetterRoutes(route: Route) {
        try {
            // Note that the engine will be internally stopped if it was started before
            // Therefore, it's not necessary to stop the engine before starting it again
            dynamicRoutingEngine.start(route, object : DynamicRoutingListener {
                // Notifies on traffic-optimized routes that are considered better than the current route
                override fun onBetterRouteFound(newRoute: Route, etaDifferenceInSeconds: Int, distanceDifferenceInMeters: Int) {
                    Log.d(TAG, "DynamicRoutingEngine: Calculated a new route.")
                    Log.d(TAG, "DynamicRoutingEngine: etaDifferenceInSeconds: $etaDifferenceInSeconds.")
                    Log.d(TAG, "DynamicRoutingEngine: distanceDifferenceInMeters: $distanceDifferenceInMeters.")

                    val logMessage = "Calculated a new route. etaDifferenceInSeconds: $etaDifferenceInSeconds " +
                            "distanceDifferenceInMeters: $distanceDifferenceInMeters"
                    messageView.setText("DynamicRoutingEngine update: $logMessage")

                    // An implementation needs to decide when to switch to the new route based
                    // on above criteria - for example, by setting visualNavigator.setRoute(newRoute)
                }

                override fun onRoutingError(routingError: RoutingError) {
                    Log.d(TAG, "Error while dynamically searching for a better route: ${routingError.name}")
                }
            })
        } catch (e: DynamicRoutingEngine.StartException) {
            throw RuntimeException("Start of DynamicRoutingEngine failed. Is the RouteHandle missing?")
        }
    }
    
    /**
     * Stop navigation and switch to tracking mode
     * 
     * @param isCameraTrackingEnabled If true, enable camera tracking in tracking mode
     */
    fun stopNavigation(isCameraTrackingEnabled: Boolean) {
        // Switches to tracking mode when a route was set before, otherwise tracking mode is kept
        // Note that tracking mode means that the visual navigator will continue to run, but without
        // turn-by-turn instructions - this can be done with or without camera tracking
        // Without a route the navigator will only notify on the current map-matched location
        // including info such as speed and current street name
        visualNavigator.setRoute(null)
        
        // SpeedBasedCameraBehavior is recommended for tracking mode
//        visualNavigator.setCameraBehavior(SpeedBasedCameraBehavior())
        
        // Switch to device positioning
        enableDevicePositioning()
        messageView.setText("Tracking device's location.")

        // Stop dynamic routing and prefetching
        dynamicRoutingEngine.stop()
        routePrefetcher.stopPrefetchAroundRoute()
        
        // Synchronize with the toggle button state
        updateCameraTracking(isCameraTrackingEnabled)
    }
    
    /**
     * Update camera tracking based on user preference
     * 
     * @param isCameraTrackingEnabled If true, enable camera tracking; otherwise disable it
     */
    private fun updateCameraTracking(isCameraTrackingEnabled: Boolean) {
        if (isCameraTrackingEnabled) {
            startCameraTracking()
        } else {
            stopCameraTracking()
        }
    }
    
    /**
     * Provides simulated location updates based on the given route
     * 
     * @param route The route to simulate movement along
     */
    fun enableRoutePlayback(route: Route) {
        herePositioningProvider.stopLocating()
        herePositioningSimulator.startLocating(visualNavigator, route)
    }
    
    /**
     * Provides location updates based on the device's GPS sensor
     */
    fun enableDevicePositioning() {
        herePositioningSimulator.stopLocating()
        herePositioningProvider.startLocating(visualNavigator, LocationAccuracy.NAVIGATION)
    }
    
    /**
     * Enable camera tracking mode (auto-follow)
     */
    fun startCameraTracking() {
        visualNavigator.setCameraBehavior(DynamicCameraBehavior())
    }
    
    /**
     * Disable camera tracking mode
     */
    fun stopCameraTracking() {
        visualNavigator.setCameraBehavior(null)
    }
    
    /**
     * Get the last known device location
     * 
     * @return The last known location, or null if not available
     */
    fun getLastKnownLocation(): Location? {
        return herePositioningProvider.getLastKnownLocation()
    }
    
    /**
     * Stop receiving location updates
     */
    fun stopLocating() {
        herePositioningProvider.stopLocating()
    }
    
    /**
     * Stop rendering the navigation view
     * It is recommended to call this before leaving an activity
     */
    fun stopRendering() {
        // It is recommended to stop rendering before leaving an activity
        // This also removes the current location marker
        visualNavigator.stopRendering()
    }
}
