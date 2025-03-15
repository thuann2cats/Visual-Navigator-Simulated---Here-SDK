package com.example.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import androidx.core.content.ContextCompat
import com.here.sdk.core.Color
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.GeoPolyline
import com.here.sdk.core.Location
import com.here.sdk.gestures.GestureState
import com.here.sdk.mapview.LineCap
import com.here.sdk.mapview.MapImage
import com.here.sdk.mapview.MapImageFactory
import com.here.sdk.mapview.MapMarker
import com.here.sdk.mapview.MapMeasure
import com.here.sdk.mapview.MapMeasureDependentRenderSize
import com.here.sdk.mapview.MapPolyline
import com.here.sdk.mapview.MapView
import com.here.sdk.mapview.RenderSize
import com.here.sdk.routing.Route
import com.here.sdk.routing.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * An app that allows to calculate a route and start navigation, using either platform positioning or
 * simulated locations.
 */
class App(
    private val context: Context,
    private val mapView: MapView,
    private val messageHandler: (String) -> Unit // Lambda function to handle messages instead of TextView
) {

    companion object {
        // Default map center coordinates (Berlin, Germany)
        val DEFAULT_MAP_CENTER = GeoCoordinates(52.520798, 13.409408)
        
        // Default distance in meters for initial zoom level
        const val DEFAULT_DISTANCE_IN_METERS = 1000 * 2
        
        private val TAG = App::class.java.simpleName
    }

    // Lists to store map markers and polylines
    private val mapMarkerList = mutableListOf<MapMarker>()
    private val mapPolylines = mutableListOf<MapPolyline>()
    
    // Route waypoints
    private var startWaypoint: Waypoint? = null
    private var destinationWaypoint: Waypoint? = null
    private var setLongpressDestination = false
    
    // Navigation components
    private val routeCalculator = RouteCalculator()
    private val navigationExample: NavigationExample
    private var isCameraTrackingEnabled = true
    private val timeUtils = TimeUtils()

    // Dialog state for Compose UI
    private val _showDialogState = MutableStateFlow<DialogState?>(null)
    val showDialogState: StateFlow<DialogState?> = _showDialogState.asStateFlow()

    // Custom implementation of TextView-like interface for NavigationExample
    private val messageView = object : MessageDisplay {
        override fun setText(text: CharSequence) {
            messageHandler(text.toString())
        }
    }

    init {
        // Set initial camera position and zoom level
        val mapMeasureZoom = MapMeasure(MapMeasure.Kind.DISTANCE, DEFAULT_DISTANCE_IN_METERS.toDouble())
        mapView.camera.lookAt(DEFAULT_MAP_CENTER, mapMeasureZoom)
        
        // Initialize navigation example with the context, map view and our message display interface
        navigationExample = NavigationExample(context, mapView, messageView)
        
        // Start receiving location updates
        navigationExample.startLocationProvider()
        
        // Set up long press gesture handler for selecting waypoints
        setLongPressGestureHandler()
        
        // Display initial instruction message
        messageHandler("Long press to set start/destination or use random ones.")
    }

    // Interface to mimic basic TextView functionality
    interface MessageDisplay {
        fun setText(text: CharSequence)
    }

    // Data classes for dialog state
    sealed class DialogState {
        data class ErrorDialog(val title: String, val message: String) : DialogState()
        data class NavigationDialog(
            val title: String, 
            val message: String, 
            val buttonText: String,
            val route: Route, 
            val isSimulated: Boolean
        ) : DialogState()
    }

    /**
     * Calculate a route and start navigation using a location simulator.
     * Start is map center and destination location is set random within viewport,
     * unless a destination is set via long press.
     */
    fun addRouteSimulatedLocation() {
        calculateRoute(true)
    }

    /**
     * Calculate a route and start navigation using locations from device.
     * Start is current location and destination is set random within viewport,
     * unless a destination is set via long press.
     */
    fun addRouteDeviceLocation() {
        calculateRoute(false)
    }

    /**
     * Clear the map and stop navigation
     */
    fun clearMapButtonPressed() {
        clearMap()
    }

    /**
     * Enable camera tracking mode
     */
    fun toggleTrackingButtonOnClicked() {
        // By default, this is enabled
        navigationExample.startCameraTracking()
        isCameraTrackingEnabled = true
    }

    /**
     * Disable camera tracking mode
     */
    fun toggleTrackingButtonOffClicked() {
        navigationExample.stopCameraTracking()
        isCameraTrackingEnabled = false
    }

    /**
     * Calculate a route based on waypoints and start navigation
     * 
     * @param isSimulated If true, uses simulated location; otherwise uses device GPS
     */
    private fun calculateRoute(isSimulated: Boolean) {
        clearMap()

        if (!determineRouteWaypoints(isSimulated)) {
            return
        }

        // Calculates a car route using HERE SDK's routing engine
        routeCalculator.calculateRoute(
            startWaypoint!!, // Safe to use !! here as we've checked in determineRouteWaypoints
            destinationWaypoint!!, // Same as above
            { routingError, routes ->
                if (routingError == null && routes != null) {
                    val route = routes[0]
                    showRouteOnMap(route)
                    showRouteDetails(route, isSimulated)
                } else {
                    showDialog("Error while calculating a route:", routingError.toString())
                }
            }
        )
    }

    /**
     * Determine start and destination waypoints for route calculation
     * 
     * @param isSimulated If true, uses simulated location; otherwise uses device GPS
     * @return true if waypoints were successfully determined, false otherwise
     */
    private fun determineRouteWaypoints(isSimulated: Boolean): Boolean {
        if (!isSimulated && navigationExample.getLastKnownLocation() == null) {
            showDialog("Error", "No GPS location found.")
            return false
        }

        // When using real GPS locations, we always start from the current location of user
        if (!isSimulated) {
            val location = navigationExample.getLastKnownLocation()!!
            startWaypoint = Waypoint(location.coordinates).apply {
                // If a driver is moving, the bearing value can help to improve the route calculation
                headingInDegrees = location.bearingInDegrees
            }
            mapView.camera.lookAt(location.coordinates)
        }

        // If no start waypoint was set (via long press or GPS), create a random one
        if (startWaypoint == null) {
            startWaypoint = Waypoint(createRandomGeoCoordinatesAroundMapCenter())
        }

        // If no destination waypoint was set (via long press), create a random one
        if (destinationWaypoint == null) {
            destinationWaypoint = Waypoint(createRandomGeoCoordinatesAroundMapCenter())
        }

        return true
    }

    /**
     * Show route details and prompt to start navigation
     * 
     * @param route The calculated route
     * @param isSimulated If true, uses simulated location; otherwise uses device GPS
     */
    private fun showRouteDetails(route: Route, isSimulated: Boolean) {
        val estimatedTravelTimeInSeconds = route.duration.seconds
        val lengthInMeters = route.lengthInMeters

        val routeDetails = "Travel Time: ${timeUtils.formatTime(estimatedTravelTimeInSeconds)}, " +
                "Length: ${timeUtils.formatLength(lengthInMeters)}"

        val buttonText = if (isSimulated) {
            "Start navigation (simulated)"
        } else {
            "Start navigation (device location)"
        }

        // Show dialog using Compose - update the dialog state
        _showDialogState.value = DialogState.NavigationDialog(
            title = "Route Details", 
            message = routeDetails,
            buttonText = buttonText,
            route = route, 
            isSimulated = isSimulated
        )
    }

    /**
     * Start navigation based on user confirmation from dialog
     * 
     * @param route The route to navigate
     * @param isSimulated Whether to use simulated location
     */
    fun startNavigation(route: Route, isSimulated: Boolean) {
        navigationExample.startNavigation(route, isSimulated, isCameraTrackingEnabled)
        // Reset dialog state after navigation starts
        _showDialogState.value = null
    }

    /**
     * Display the route on the map as a polyline
     * 
     * @param route The calculated route to display
     */
    private fun showRouteOnMap(route: Route) {
        // Show route as a polyline on the map
        val routeGeoPolyline = route.geometry
        val widthInPixels = 20f
        val polylineColor = Color.valueOf(0f, 0.56f, 0.54f, 0.63f)
        
        try {
            // Create a map polyline representation with specified style
            val routeMapPolyline = MapPolyline(
                routeGeoPolyline,
                MapPolyline.SolidRepresentation(
                    MapMeasureDependentRenderSize(RenderSize.Unit.PIXELS, widthInPixels * 1.0),
                    polylineColor,
                    LineCap.ROUND
                )
            )
            
            // Add polyline to the map and track it for later removal
            mapView.mapScene.addMapPolyline(routeMapPolyline)
            mapPolylines.add(routeMapPolyline)
            
        } catch (e: MapPolyline.Representation.InstantiationException) {
            Log.e(TAG, "MapPolyline Representation Exception: ${e.error.name}")
        } catch (e: MapMeasureDependentRenderSize.InstantiationException) {
            Log.e(TAG, "MapMeasureDependentRenderSize Exception: ${e.error.name}")
        }
    }

    /**
     * Clear the map by removing waypoint markers and route polylines
     */
    fun clearMap() {
        clearWaypointMapMarker()
        clearRoute()

        navigationExample.stopNavigation(isCameraTrackingEnabled)
    }

    /**
     * Remove all waypoint markers from the map
     */
    private fun clearWaypointMapMarker() {
        mapMarkerList.forEach { marker ->
            mapView.mapScene.removeMapMarker(marker)
        }
        mapMarkerList.clear()
    }

    /**
     * Remove all route polylines from the map
     */
    private fun clearRoute() {
        mapPolylines.forEach { polyline ->
            mapView.mapScene.removeMapPolyline(polyline)
        }
        mapPolylines.clear()
    }

    /**
     * Set up the long press gesture handler for setting start/destination points
     */
    private fun setLongPressGestureHandler() {
        mapView.gestures.setLongPressListener { gestureState, touchPoint ->
            val geoCoordinates = mapView.viewToGeoCoordinates(touchPoint) ?: return@setLongPressListener
            
            if (gestureState == GestureState.BEGIN) {
                if (setLongpressDestination) {
                    // Set destination waypoint
                    destinationWaypoint = Waypoint(geoCoordinates)
                    addCircleMapMarker(geoCoordinates, R.drawable.green_dot)
                    messageHandler("Destination has been set.")
                } else {
                    // Set start waypoint
                    startWaypoint = Waypoint(geoCoordinates)
                    addCircleMapMarker(geoCoordinates, R.drawable.green_dot)
                    messageHandler("Starting point has been set.")
                }
                // Toggle between setting start/destination for next long press
                setLongpressDestination = !setLongpressDestination
            }
        }
    }

    /**
     * Create random coordinates around the map center
     * 
     * @return Randomly generated GeoCoordinates
     */
    private fun createRandomGeoCoordinatesAroundMapCenter(): GeoCoordinates {
        val centerGeoCoordinates = getMapViewCenter()
        val lat = centerGeoCoordinates.latitude
        val lon = centerGeoCoordinates.longitude
        return GeoCoordinates(
            getRandom(lat - 0.02, lat + 0.02),
            getRandom(lon - 0.02, lon + 0.02)
        )
    }

    /**
     * Generate a random double value between min and max
     */
    private fun getRandom(min: Double, max: Double): Double {
        return min + Math.random() * (max - min)
    }

    /**
     * Get the current center coordinates of the map view
     */
    private fun getMapViewCenter(): GeoCoordinates {
        return mapView.camera.state.targetCoordinates
    }

    fun getBitmapFromVectorDrawable(context: Context, drawableId: Int): Bitmap? {
        val drawable = ContextCompat.getDrawable(context, drawableId) ?: return null

        val bitmap = Bitmap.createBitmap(
            drawable.intrinsicWidth.takeIf { it > 0 } ?: 32, // Default size if -1
            drawable.intrinsicHeight.takeIf { it > 0 } ?: 32, // Default size if -1
            Bitmap.Config.ARGB_8888
        )

        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)

        return bitmap
    }

    /**
     * Add a circle marker at the specified coordinates
     * 
     * @param geoCoordinates The coordinates where to place the marker
     * @param resourceId The resource ID of the marker image
     */
    private fun addCircleMapMarker(geoCoordinates: GeoCoordinates, resourceId: Int) {
        val bitmap = getBitmapFromVectorDrawable(context, resourceId)
//        val mapImage = MapImageFactory.fromResource(context.resources, resourceId)
        val mapImage = MapImageFactory.fromBitmap(bitmap!!)
        val mapMarker = MapMarker(geoCoordinates, mapImage)

        mapView.mapScene.addMapMarker(mapMarker)
        mapMarkerList.add(mapMarker)
    }

    /**
     * Show a dialog with the specified title and message
     * Updates dialog state for Compose UI
     */
    private fun showDialog(title: String, message: String) {
        _showDialogState.value = DialogState.ErrorDialog(title, message)
    }

    /**
     * Dismiss any currently showing dialog
     */
    fun dismissDialog() {
        _showDialogState.value = null
    }

    /**
     * Clean up resources when the app is detached
     */
    fun detach() {
        // Disables TBT guidance (if running) and enters tracking mode
        navigationExample.stopNavigation(isCameraTrackingEnabled)
        // Disables positioning
        navigationExample.stopLocating()
        // Disables rendering
        navigationExample.stopRendering()
    }
}
