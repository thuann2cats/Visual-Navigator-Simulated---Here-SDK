# Here SDK Visual Navigator demo

This is the "Navigation" example app from Here SDK tutorials (originally written in Java), converted to work in Jetpack Compose & Kotlin. The location movement is simulated to demonstrate the visual navigator's behaviors.

![Demo Screenshot](./Screenshot%202025-03-15%20121642.png)

[Video](https://photos.google.com/share/AF1QipNeNzPCMpNXyba0d8DS-odMMoFMBijIoCUZmN1KLkgwbH34chDN2z0guZLH96Futw/photo/AF1QipOF-UmQn30AY97gQRsJ3MEWN-pqvLIX9qxlVas_?key=MFBscFdkeFJ3MnQ1N1Z1TzJlTmFCOXhrMWpxN1Jn)



Original "Navigation" example app: [here-sdk-examples/examples/latest/navigate/android/Navigation at master · heremaps/here-sdk-examples](https://github.com/heremaps/here-sdk-examples/tree/master/examples/latest/navigate/android/Navigation)

## Setup steps:
- Be sure to put your .aar Here SDK library in the right folder (as usual)
- Put the .env file in the root of your project and fill in the necessary API keys (as usual)
- Long-press to set the starting way point. Then long-press again to set the destination way point. You might need to disable camera tracking to be able to pan/zoom easily. Then tap "Simulated" to simulate navigation.

## Important breakpoints to examine

### Setting up various Here SDK objects (while loading the app)

#### VisualNavigator
[NavigationExample.kt:58](./app/src/main/java/com/example/navigation/NavigationExample.kt#L58)
```kotlin
init {
    // Initialize the positioning providers, navigator, and other components
    try {
        // Without a route set, this starts in tracking mode
        visualNavigator = VisualNavigator()
    } catch (e: InstantiationErrorException) {
        throw RuntimeException("Initialization of VisualNavigator failed: ${e.error.name}")
    }
```

#### RouteCalculator
[App.kt:59](./app/src/main/java/com/example/navigation/App.kt#L59)
```kotlin
private val routeCalculator = RouteCalculator()
```

#### LocationEngine
[HEREPositioningProvider.kt:44](./app/src/main/java/com/example/navigation/HEREPositioningProvider.kt#L44)
```kotlin
try {
    consentEngine = ConsentEngine()
    locationEngine = LocationEngine()
} catch (e: InstantiationErrorException) {
    throw RuntimeException("Initialization failed: ${e.message}")
}
```

#### VisualNavigator added to LocationEngine to receive location updates
[HEREPositioningProvider.kt:77](./app/src/main/java/com/example/navigation/HEREPositioningProvider.kt#L77)
```kotlin
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
```

### Long-press listeners to set start/destination waypoints
[App.kt:323](./app/src/main/java/com/example/navigation/App.kt#L323)
```kotlin
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
```

### When the navigation starts, the route needs to be calculated:
[RouteCalculator.kt:36](./app/src/main/java/com/example/navigation/RouteCalculator.kt#L36)
```kotlin
routingEngine.calculateRoute(
    waypoints,
    routingOptions,
    calculateRouteCallback
)
```

### Once computed, the route polylines need to be drawn on the map
[App.kt:171](./app/src/main/java/com/example/navigation/App.kt#L171)
```kotlin
if (routingError == null && routes != null) {
    val route = routes[0]
    showRouteOnMap(route)
    showRouteDetails(route, isSimulated)
} else {
    showDialog("Error while calculating a route:", routingError.toString())
}
```

### The visual navigator is set to follow that route
[NavigationExample.kt:150](./app/src/main/java/com/example/navigation/NavigationExample.kt#L150)
```kotlin
visualNavigator.setRoute(route)

// Enable auto-zoom during guidance
visualNavigator.setCameraBehavior(DynamicCameraBehavior())
```

### Voice assistant's directions upon receiving updates on new progress along the route:
Various breakpoints in [NavigationEventHandler.kt](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt) for various event listeners:
- [Line 66](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L66): Setup listeners
- [Line 120](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L120): Route Progress Listener
- [Line 133](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L133): Destination Reached Listener
- [Line 155](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L155): Milestone Status Listener
- [Line 178](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L178): Safety Camera Warning Listener
- [Line 197](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L197): Speed Warning Listener
- [Line 236](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L236): Navigable Location Listener
- [Line 274](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L274): Route Deviation Listener
- [Line 288](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L288): Event Text Listener
- [Line 301](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L301): Lane Assistance Listeners
- [Line 317](./app/src/main/java/com/example/navigation/NavigationEventHandler.kt#L317): Road Attributes Listener

In this demo app, most events do not result in any UI feedbacks and only log the information on LogCat. Please open LogCat and filter for the appropriate tag (for example, `NavigationEventHandler` to see those messages).

Some examples include:

- **Route Progress Listener** (setRouteProgressListener)  
  Notifies progress along the route, including maneuver instructions.

- **Destination Reached Listener** (setDestinationReachedListener)  
  Notifies when the destination is reached.

- **Milestone Status Listener** (setMilestoneStatusListener)  
  Notifies when a waypoint is reached or missed.

- **Safety Camera Warning Listener** (setSafetyCameraWarningListener)  
  Alerts when approaching or passing a safety camera.

- **Speed Warning Listener** (setSpeedWarningListener)  
  Alerts when exceeding the speed limit.

- **Speed Limit Listener** (setSpeedLimitListener)  
  Provides current speed limit updates.

- **Navigable Location Listener** (setNavigableLocationListener)  
  Provides real-time location updates, including wrong-way driving warnings.

- **Route Deviation Listener** (setRouteDeviationListener)  
  Notifies when the driver deviates from the planned route.

- **Event Text Listener** (setEventTextListener)  
  Handles navigation messages for text-to-speech (TTS) announcements.

- **Maneuver View Lane Assistance Listener** (setManeuverViewLaneAssistanceListener)  
  Provides lane recommendations for upcoming maneuvers.

- **Junction View Lane Assistance Listener** (setJunctionViewLaneAssistanceListener)  
  Provides lane guidance at complex junctions.

- **Road Attributes Listener** (setRoadAttributesListener)  
  Provides road attribute updates like bridges, tunnels, private roads, etc.
