package com.example.navigation

import android.content.Context
import android.media.RingtoneManager
import android.util.Log
import com.here.sdk.core.GeoCoordinates
import com.here.sdk.core.LanguageCode
import com.here.sdk.core.UnitSystem
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.navigation.*
import com.here.sdk.routing.*
import com.here.sdk.trafficawarenavigation.DynamicRoutingEngine
import com.here.sdk.transport.GeneralVehicleSpeedLimits
import java.util.*

/**
 * This class combines the various events that can be emitted during turn-by-turn navigation.
 * Note that this class does not show an exhaustive list of all possible events.
 */
class NavigationEventHandler(
    private val context: Context,
    private val messageView: App.MessageDisplay // Changed from TextView to our MessageDisplay interface
) {
    
    companion object {
        private val TAG = NavigationEventHandler::class.java.simpleName
    }
    
    private var previousManeuverIndex = -1
    private var lastMapMatchedLocation: MapMatchedLocation? = null
    
    // Helper classes for navigation
    private val voiceAssistant = VoiceAssistant(context)
    private val timeUtils = TimeUtils()
    private val routingEngine: RoutingEngine
    
    // Track last traffic update time
    private var lastTrafficUpdateInMilliseconds: Long = 0
    
    init {
        try {
            // Initialize the routing engine for traffic calculations
            routingEngine = RoutingEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of RoutingEngine failed: ${e.error.name}")
        }
    }

    /**
     * Sets up all the listeners needed for navigation events on the Visual Navigator
     * 
     * @param visualNavigator The VisualNavigator used for navigation
     * @param dynamicRoutingEngine The DynamicRoutingEngine used for traffic-aware routing
     */
    fun setupListeners(visualNavigator: VisualNavigator, dynamicRoutingEngine: DynamicRoutingEngine) {
        
        // Set up speed warnings and voice guidance
        setupSpeedWarnings(visualNavigator)
        setupVoiceGuidance(visualNavigator)
        
        // ----------------------------- ROUTE PROGRESS LISTENER -----------------------------
        // Notifies on the progress along the route including maneuver instructions
        visualNavigator.setRouteProgressListener { routeProgress ->
            
            // Contains the progress for the next maneuver ahead and the next-next maneuvers, if any
            val nextManeuverList = routeProgress.maneuverProgress

            val nextManeuverProgress = nextManeuverList.getOrNull(0) ?: run {
                Log.d(TAG, "No next maneuver available.")
                return@setRouteProgressListener
            }
                        
            val nextManeuverIndex = nextManeuverProgress.maneuverIndex
            val nextManeuver = visualNavigator.getManeuver(nextManeuverIndex) ?: return@setRouteProgressListener
                        
            val action = nextManeuver.action
            val roadName = getRoadName(nextManeuver)
            val logMessage = "${action.name} on $roadName in ${nextManeuverProgress.remainingDistanceInMeters} meters."
            
            // Angle is null for some maneuvers like Depart, Arrive and Roundabout
            nextManeuver.turnAngleInDegrees?.let { turnAngle ->
                when {
                    turnAngle > 10 -> Log.d(TAG, "At the next maneuver: Make a right turn of $turnAngle degrees.")
                    turnAngle < -10 -> Log.d(TAG, "At the next maneuver: Make a left turn of $turnAngle degrees.")
                    else -> Log.d(TAG, "At the next maneuver: Go straight.")
                }
            }
            
            // Angle is null when the roundabout maneuver is not an enter, exit or keep maneuver
            nextManeuver.roundaboutAngleInDegrees?.let { roundaboutAngle ->
                // Note that the value is negative only for left-driving countries such as UK
                Log.d(TAG, "At the next maneuver: Follow the roundabout for $roundaboutAngle degrees to reach the exit.")
            }
            
            var currentETAString = getETA(routeProgress)
            
            if (previousManeuverIndex != nextManeuverIndex) {
                currentETAString = "$currentETAString\nNew maneuver: $logMessage"
            } else {
                // A maneuver update contains a different distance to reach the next maneuver
                currentETAString = "$currentETAString\nManeuver update: $logMessage"
            }
            messageView.setText(currentETAString)  // Changed from text = to setText()
            
            previousManeuverIndex = nextManeuverIndex
            
            // Update the route based on the current location of the driver
            lastMapMatchedLocation?.let { location ->
                // We periodically want to search for better traffic-optimized routes
                dynamicRoutingEngine.updateCurrentLocation(location, routeProgress.sectionIndex)
            }
            
            // Update traffic information periodically
            updateTrafficOnRoute(routeProgress, visualNavigator)
        }
        
        // ----------------------------- DESTINATION REACHED LISTENER -----------------------------
        // Notifies when the destination of the route is reached
        visualNavigator.setDestinationReachedListener {
            val message = "Destination reached."
            messageView.setText(message)  // Changed from text = to setText()
            
            // Guidance has stopped. Now consider to, for example,
            // switch to tracking mode or stop rendering or locating or do anything else that may
            // be useful to support your app flow.
            // If the DynamicRoutingEngine was started before, consider to stop it now.
        }
        
        // ----------------------------- MILESTONE STATUS LISTENER -----------------------------
        // Notifies when a waypoint on the route is reached or missed
        visualNavigator.setMilestoneStatusListener { milestone, milestoneStatus ->
            when {
                milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.REACHED -> {
                    Log.d(TAG, "A user-defined waypoint was reached, index of waypoint: ${milestone.waypointIndex}")
                    Log.d(TAG, "Original coordinates: ${milestone.originalCoordinates}")
                }
                milestone.waypointIndex != null && milestoneStatus == MilestoneStatus.MISSED -> {
                    Log.d(TAG, "A user-defined waypoint was missed, index of waypoint: ${milestone.waypointIndex}")
                    Log.d(TAG, "Original coordinates: ${milestone.originalCoordinates}")
                }
                milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.REACHED -> {
                    // For example, when transport mode changes due to a ferry a system-defined waypoint may have been added
                    Log.d(TAG, "A system-defined waypoint was reached at: ${milestone.mapMatchedCoordinates}")
                }
                milestone.waypointIndex == null && milestoneStatus == MilestoneStatus.MISSED -> {
                    // For example, when transport mode changes due to a ferry a system-defined waypoint may have been added
                    Log.d(TAG, "A system-defined waypoint was missed at: ${milestone.mapMatchedCoordinates}")
                }
            }
        }
        
        // ----------------------------- SAFETY CAMERA WARNING LISTENER -----------------------------
        // Notifies on safety camera warnings as they appear along the road
        visualNavigator.setSafetyCameraWarningListener { safetyCameraWarning ->
            when (safetyCameraWarning.distanceType) {
                DistanceType.AHEAD -> {
                    Log.d(TAG, "Safety camera warning ${safetyCameraWarning.type.name} ahead in: " +
                            "${safetyCameraWarning.distanceToCameraInMeters} with speed limit = " +
                            "${safetyCameraWarning.speedLimitInMetersPerSecond} m/s")
                }
                DistanceType.PASSED -> {
                    Log.d(TAG, "Safety camera warning ${safetyCameraWarning.type.name} passed: " +
                            "${safetyCameraWarning.distanceToCameraInMeters} with speed limit = " +
                            "${safetyCameraWarning.speedLimitInMetersPerSecond} m/s")
                }
                DistanceType.REACHED -> {
                    Log.d(TAG, "Safety camera warning ${safetyCameraWarning.type.name} reached at: " +
                            "${safetyCameraWarning.distanceToCameraInMeters} with speed limit = " +
                            "${safetyCameraWarning.speedLimitInMetersPerSecond} m/s")
                }
                else -> {} // Handle other cases if needed
            }
        }
        
        // ----------------------------- SPEED WARNING LISTENER -----------------------------
        // Notifies when the current speed limit is exceeded
        visualNavigator.setSpeedWarningListener { speedWarningStatus ->
            when (speedWarningStatus) {
                SpeedWarningStatus.SPEED_LIMIT_EXCEEDED -> {
                    // Driver is faster than current speed limit (plus an optional offset)
                    // Play a notification sound to alert the driver
                    // Note that this may not include temporary special speed limits, see SpeedLimitListener
                    val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val ringtone = RingtoneManager.getRingtone(context, ringtoneUri)
                    ringtone.play()
                }
                SpeedWarningStatus.SPEED_LIMIT_RESTORED -> {
                    Log.d(TAG, "Driver is again slower than current speed limit (plus an optional offset).")
                }
                else -> {} // Handle other cases if needed
            }
        }
        
        // ----------------------------- SPEED LIMIT LISTENER -----------------------------
        // Notifies on the current speed limit valid on the current road
        visualNavigator.setSpeedLimitListener { speedLimit ->
            val currentSpeedLimit = getCurrentSpeedLimit(speedLimit)
            
            when {
                currentSpeedLimit == null -> {
                    Log.d(TAG, "Warning: Speed limits unknown, data could not be retrieved.")
                }
                currentSpeedLimit == 0.0 -> {
                    Log.d(TAG, "No speed limits on this road! Drive as fast as you feel safe ...")
                }
                else -> {
                    Log.d(TAG, "Current speed limit (m/s): $currentSpeedLimit")
                }
            }
        }
        
        // ----------------------------- NAVIGABLE LOCATION LISTENER -----------------------------
        // Notifies on the current map-matched location and other useful information while driving or walking
        visualNavigator.setNavigableLocationListener { currentNavigableLocation ->
            lastMapMatchedLocation = currentNavigableLocation.mapMatchedLocation
            
            if (lastMapMatchedLocation == null) {
                Log.d(TAG, "The currentNavigableLocation could not be map-matched. Are you off-road?")
                return@setNavigableLocationListener
            }
            
            if (lastMapMatchedLocation!!.isDrivingInTheWrongWay) {
                // For two-way streets, this value is always false. 
                // This feature is supported in tracking mode and when deviating from a route
                Log.d(TAG, "This is a one way road. User is driving against the allowed traffic direction.")
            }
            
            val speed = currentNavigableLocation.originalLocation.speedInMetersPerSecond
            val accuracy = currentNavigableLocation.originalLocation.speedAccuracyInMetersPerSecond
            Log.d(TAG, "Driving speed (m/s): $speed plus/minus an accuracy of: $accuracy")
        }
        
        // ----------------------------- ROUTE DEVIATION LISTENER -----------------------------
        // Notifies on a possible deviation from the route
        visualNavigator.setRouteDeviationListener { routeDeviation ->
            val route = visualNavigator.route ?: return@setRouteDeviationListener
            
            // Get current geographic coordinates
            val currentMapMatchedLocation = routeDeviation.currentLocation.mapMatchedLocation
            val currentGeoCoordinates = currentMapMatchedLocation?.coordinates 
                ?: routeDeviation.currentLocation.originalLocation.coordinates
            
            // Get last geographic coordinates on route
            val lastGeoCoordinatesOnRoute = if (routeDeviation.lastLocationOnRoute != null) {
                val lastMapMatchedLocationOnRoute = routeDeviation.lastLocationOnRoute?.mapMatchedLocation
                lastMapMatchedLocationOnRoute?.coordinates 
                    ?: routeDeviation.lastLocationOnRoute?.originalLocation?.coordinates
            } else {
                Log.d(TAG, "User was never following the route. So, we take the start of the route instead.")
                route.sections[0].departurePlace.originalCoordinates
            }
            
            lastGeoCoordinatesOnRoute?.let { lastCoordinates ->
                val distanceInMeters = currentGeoCoordinates.distanceTo(lastCoordinates).toInt()
                Log.d(TAG, "RouteDeviation in meters is $distanceInMeters")
            }
            
            // Now, an application needs to decide if the user has deviated far enough and
            // what should happen next: For example, you can notify the user or simply try to
            // calculate a new route. When you calculate a new route, you can, for example,
            // take the current location as new start and keep the destination - another
            // option could be to calculate a new route back to the lastMapMatchedLocationOnRoute.
            // At least, make sure to not calculate a new route every time you get a RouteDeviation
            // event as the route calculation happens asynchronously and takes also some time to
            // complete.
            // The deviation event is sent any time an off-route location is detected: It may make
            // sense to await around 3 events before deciding on possible actions.
        }
        
        // ----------------------------- EVENT TEXT LISTENER -----------------------------
        // Notifies on messages that can be fed into TTS engines to guide the user with audible instructions
        visualNavigator.setEventTextListener { eventText ->
            // We use the built-in TTS engine to synthesize the localized text as audio
            voiceAssistant.speak(eventText.text)
            
            // We can optionally retrieve the associated maneuver. The details will be null if the text contains
            // non-maneuver related information, such as for speed camera warnings.
            if (eventText.type == TextNotificationType.MANEUVER && eventText.maneuverNotificationDetails != null) {
                val maneuver = eventText.maneuverNotificationDetails?.maneuver
                // Process maneuver if needed
            }
        }
        
        // ----------------------------- LANE ASSISTANCE LISTENERS -----------------------------
        // Notifies which lane(s) lead to the next (next) maneuvers
        visualNavigator.setManeuverViewLaneAssistanceListener { maneuverViewLaneAssistance ->
            // This lane list is guaranteed to be non-empty
            val lanes = maneuverViewLaneAssistance.lanesForNextManeuver
            logLaneRecommendations(lanes)
            
            val nextLanes = maneuverViewLaneAssistance.lanesForNextNextManeuver
            if (nextLanes.isNotEmpty()) {
                Log.d(TAG, "Attention, the next next maneuver is very close.")
                Log.d(TAG, "Please take the following lane(s) after the next maneuver: ")
                logLaneRecommendations(nextLanes)
            }
        }
        
        // Notifies which lane(s) allow to follow the route at complex junctions
        visualNavigator.setJunctionViewLaneAssistanceListener { junctionViewLaneAssistance ->
            val lanes = junctionViewLaneAssistance.lanesForNextJunction
            if (lanes.isEmpty()) {
                Log.d(TAG, "You have passed the complex junction.")
            } else {
                Log.d(TAG, "Attention, a complex junction is ahead.")
                logLaneRecommendations(lanes)
            }
        }
        
        // ----------------------------- ROAD ATTRIBUTES LISTENER -----------------------------
        // Notifies on the attributes of the current road including usage and physical characteristics
        visualNavigator.setRoadAttributesListener { roadAttributes ->
            // This is called whenever any road attribute has changed
            // If all attributes are unchanged, no new event is fired
            // Note that a road can have more than one attribute at the same time
            
            Log.d(TAG, "Received road attributes update.")
            
            if (roadAttributes.isBridge) {
                // Identifies a structure that allows a road, railway, or walkway to pass over another road, railway,
                // waterway, or valley serving map display and route guidance functionalities
                Log.d(TAG, "Road attributes: This is a bridge.")
            }
            if (roadAttributes.isControlledAccess) {
                // Controlled access roads are roads with limited entrances and exits that allow uninterrupted
                // high-speed traffic flow
                Log.d(TAG, "Road attributes: This is a controlled access road.")
            }
            if (roadAttributes.isDirtRoad) {
                // Indicates whether the navigable segment is paved
                Log.d(TAG, "Road attributes: This is a dirt road.")
            }
            if (roadAttributes.isDividedRoad) {
                // Indicates if there is a physical structure or painted road marking intended to legally prohibit
                // left turns in right-side driving countries, right turns in left-side driving countries,
                // and U-turns at divided intersections or in the middle of divided segments
                Log.d(TAG, "Road attributes: This is a divided road.")
            }
            if (roadAttributes.isNoThrough) {
                // Identifies a no through road
                Log.d(TAG, "Road attributes: This is a no through road.")
            }
            if (roadAttributes.isPrivate) {
                // Private identifies roads that are not maintained by an organization responsible for maintenance of
                // public roads
                Log.d(TAG, "Road attributes: This is a private road.")
            }
            if (roadAttributes.isRamp) {
                // Range is a ramp: connects roads that do not intersect at grade
                Log.d(TAG, "Road attributes: This is a ramp.")
            }
            if (roadAttributes.isRightDrivingSide) {
                // Indicates if vehicles have to drive on the right-hand side of the road or the left-hand side
                // For example, in New York it is always true and in London always false as the United Kingdom is
                // a left-hand driving country
                Log.d(TAG, "Road attributes: isRightDrivingSide = ${roadAttributes.isRightDrivingSide}")
            }
            if (roadAttributes.isRoundabout) {
                // Indicates the presence of a roundabout
                Log.d(TAG, "Road attributes: This is a roundabout.")
            }
            if (roadAttributes.isTollway) {
                // Identifies a road for which a fee must be paid to use the road
                Log.d(TAG, "Road attributes change: This is a road with toll costs.")
            }
            if (roadAttributes.isTunnel) {
                // Identifies an enclosed (on all sides) passageway through or under an obstruction
                Log.d(TAG, "Road attributes: This is a tunnel.")
            }
        }
        
        // Add more advanced event listeners
        setupAdvancedNavigationListeners(visualNavigator)
    }
    
    /**
     * Sets up additional navigation listeners for more specialized features
     * 
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupAdvancedNavigationListeners(visualNavigator: VisualNavigator) {
        // ----------------------------- ROAD SIGN WARNING -----------------------------
        val roadSignWarningOptions = RoadSignWarningOptions().apply {
            // Set a filter to get only shields relevant for TRUCKS and HEAVY_TRUCKS
            vehicleTypesFilter = listOf(RoadSignVehicleType.TRUCKS, RoadSignVehicleType.HEAVY_TRUCKS)
        }
        
        // Get notification distances for road sign alerts from visual navigator
        val warningNotificationDistances = visualNavigator.getWarningNotificationDistances(WarningType.ROAD_SIGN).apply {
            // The distance in meters for emitting warnings when the speed limit or current speed is fast
            fastSpeedDistanceInMeters = 1600
            // The distance in meters for emitting warnings when the speed limit or current speed is regular
            regularSpeedDistanceInMeters = 800
            // The distance in meters for emitting warnings when the speed limit or current speed is slow
            slowSpeedDistanceInMeters = 600
        }
        
        // Set the warning distances for road signs
        visualNavigator.setWarningNotificationDistances(WarningType.ROAD_SIGN, warningNotificationDistances)
        visualNavigator.setRoadSignWarningOptions(roadSignWarningOptions)
        
        // Notifies on road shields as they appear along the road
        visualNavigator.setRoadSignWarningListener { roadSignWarning ->
            Log.d(TAG, "Road sign distance (m): ${roadSignWarning.distanceToRoadSignInMeters}")
            Log.d(TAG, "Road sign type: ${roadSignWarning.type.name}")
            
            roadSignWarning.signValue?.let {
                // Optional text as it is printed on the local road sign
                Log.d(TAG, "Road sign text: ${it.text}")
            }
        }
        
        // Configure more specialized listeners like school zone warnings, border crossings, etc.
        // (Additional specialized listeners are implemented below)
        setupSchoolZoneWarnings(visualNavigator)
        setupBorderCrossingWarnings(visualNavigator)
        setupTruckRestrictionWarnings(visualNavigator)
        setupLowSpeedZoneWarnings(visualNavigator)
        setupRoadTextsListener(visualNavigator)
        setupRealisticViewWarnings(visualNavigator)
        setupTollStopWarnings(visualNavigator)
        setupDangerZoneWarnings(visualNavigator)  // Make sure danger zone warnings are set up
    }
    
    /**
     * Sets up danger zone warning listener for the visual navigator
     *
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupDangerZoneWarnings(visualNavigator: VisualNavigator) {
        // A danger zone refers to areas with increased risk of traffic incidents
        // These zones are designated to alert drivers to potential hazards and encourage safer driving
        // The HERE SDK warns when approaching the danger zone, as well as when leaving such a zone
        // A danger zone may or may not have one or more speed cameras in it
        // Note: danger zones are only available in selected countries, such as France
        visualNavigator.setDangerZoneWarningListener { dangerZoneWarning ->
            when (dangerZoneWarning.distanceType) {
                DistanceType.AHEAD -> {
                    Log.d(TAG, "A danger zone ahead in: ${dangerZoneWarning.distanceInMeters} meters.")
                    // isZoneStart indicates if we enter the danger zone from the start
                    // It's false when the danger zone is entered from a side street
                    // Based on the route path, the HERE SDK anticipates from where the danger zone will be entered
                    // In tracking mode, the most probable path will be used to anticipate entry point
                    Log.d(TAG, "isZoneStart: ${dangerZoneWarning.isZoneStart}")
                }
                DistanceType.REACHED -> {
                    Log.d(TAG, "A danger zone has been reached. isZoneStart: ${dangerZoneWarning.isZoneStart}")
                }
                DistanceType.PASSED -> {
                    Log.d(TAG, "A danger zone has been passed.")
                }
                else -> {} // Handle other cases if needed
            }
        }
    }
    
    /**
     * Sets up school zone warning listener for the visual navigator
     *
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupSchoolZoneWarnings(visualNavigator: VisualNavigator) {
        // Notifies on school zones as they appear along the road
        val schoolZoneWarningOptions = SchoolZoneWarningOptions().apply {
            // Set a filter to get only school zones relevant for TRUCKS and HEAVY_TRUCKS
//            vehicleTypesFilter = listOf(SchoolZoneVehicleType.TRUCKS, SchoolZoneVehicleType.HEAVY_TRUCKS)
        }
        visualNavigator.setSchoolZoneWarningOptions(schoolZoneWarningOptions)
        
        visualNavigator.setSchoolZoneWarningListener { schoolZoneWarnings ->
            for (schoolZoneWarning in schoolZoneWarnings) {
                when (schoolZoneWarning.distanceType) {
                    DistanceType.AHEAD -> {
                        Log.d(TAG, "School zone ahead in: ${schoolZoneWarning.distanceToSchoolZoneInMeters} meters.")
                        Log.d(TAG, "Speed limit restriction for this school zone: " +
                                "${schoolZoneWarning.speedLimitInMetersPerSecond} m/s.")
                    }
                    DistanceType.REACHED -> {
                        Log.d(TAG, "A school zone has been reached with speed limit: " +
                                "${schoolZoneWarning.speedLimitInMetersPerSecond} m/s.")
                    }
                    DistanceType.PASSED -> {
                        Log.d(TAG, "A school zone has been passed.")
                    }
                    else -> {} // Handle other cases if needed
                }
                
                // Check if the warning applies at the current time
                schoolZoneWarning.timeRule?.let { timeRule ->
                    if (!timeRule.appliesTo(Date())) {
                        // For example, during night sometimes a school zone warning does not apply.
                        Log.d(TAG, "Note that this school zone warning currently does not apply.")
                    }
                }
            }
        }
    }
    
    /**
     * Sets up border crossing warning listener for the visual navigator
     *
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupBorderCrossingWarnings(visualNavigator: VisualNavigator) {
        val borderCrossingWarningOptions = BorderCrossingWarningOptions().apply {

            filterOutStateBorderWarnings = false
        }
        visualNavigator.setBorderCrossingWarningOptions(borderCrossingWarningOptions)
        
        visualNavigator.setBorderCrossingWarningListener { borderCrossingWarning ->
            // Since the border crossing warning is given relative to a single location,
            // the DistanceType.REACHED will never be given for this warning
            when (borderCrossingWarning.distanceType) {
                DistanceType.AHEAD -> {
                    Log.d(TAG, "BorderCrossing: A border is ahead in: " +
                            "${borderCrossingWarning.distanceToBorderCrossingInMeters} meters.")
                    Log.d(TAG, "BorderCrossing: Type (such as country or state): ${borderCrossingWarning.type.name}")
                    Log.d(TAG, "BorderCrossing: Country code: ${borderCrossingWarning.countryCode.name}")
                    // The state code after the border crossing. It represents the state/province code.
                    // It's a 1-3 uppercase characters string that follows the ISO 3166-2 standard,
                    // but without the preceding country code (e.g., for Texas, the state code will be TX)
                    borderCrossingWarning.stateCode?.let { stateCode ->
                        Log.d(TAG, "BorderCrossing: State code: $stateCode")
                    }
                    // The general speed limits that apply in the country/state after border crossing
                    val generalVehicleSpeedLimits = borderCrossingWarning.speedLimits
                    Log.d(TAG, "BorderCrossing: Speed limit in cities (m/s): " +
                            "${generalVehicleSpeedLimits.maxSpeedUrbanInMetersPerSecond}")
                    Log.d(TAG, "BorderCrossing: Speed limit outside cities (m/s): " +
                            "${generalVehicleSpeedLimits.maxSpeedRuralInMetersPerSecond}")
                    Log.d(TAG, "BorderCrossing: Speed limit on highways (m/s): " +
                            "${generalVehicleSpeedLimits.maxSpeedHighwaysInMetersPerSecond}")
                }
                DistanceType.PASSED -> {
                    Log.d(TAG, "BorderCrossing: A border has been passed.")
                }
                else -> {} // Handle other cases if needed
            }
        }
    }
    
    /**
     * Sets up truck restriction warnings for the visual navigator
     *
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupTruckRestrictionWarnings(visualNavigator: VisualNavigator) {
        // Notifies truck drivers on road restrictions ahead
        // For example, there can be a bridge ahead not high enough to pass a big truck
        // or a road ahead where the weight of the truck is beyond its permissible weight
        visualNavigator.setTruckRestrictionsWarningListener { truckRestrictionWarnings ->
            // The list is guaranteed to be non-empty
            for (truckRestrictionWarning in truckRestrictionWarnings) {
                when (truckRestrictionWarning.distanceType) {
                    DistanceType.AHEAD -> {
                        Log.d(TAG, "TruckRestrictionWarning ahead in: ${truckRestrictionWarning.distanceInMeters} meters.")
                        
                        truckRestrictionWarning.timeRule?.let { timeRule ->
                            if (!timeRule.appliesTo(Date())) {
                                // For example, during a specific time period of a day, some truck restriction warnings don't apply
                                // If timeRule is null, the warning applies at any time
                                Log.d(TAG, "Note that this truck restriction warning currently does not apply.")
                            }
                        }
                    }
                    DistanceType.REACHED -> {
                        Log.d(TAG, "A truck restriction has been reached.")
                    }
                    DistanceType.PASSED -> {
                        // If not preceded by a "REACHED"-notification, this restriction was valid only for the passed location
                        Log.d(TAG, "A truck restriction just passed.")
                    }
                    else -> {} // Handle other cases if needed
                }
                
                // One of the following restrictions applies ahead
                // If more restrictions apply at the same time, they are part of another TruckRestrictionWarning element in the list
                when {
                    truckRestrictionWarning.weightRestriction != null -> {
                        val type = truckRestrictionWarning.weightRestriction!!.type
                        val value = truckRestrictionWarning.weightRestriction!!.valueInKilograms
                        Log.d(TAG, "TruckRestriction for weight (kg): ${type.name}: $value")
                    }
                    truckRestrictionWarning.dimensionRestriction != null -> {
                        // Can be either a length, width or height restriction of the truck
                        // For example, a height restriction can apply for a tunnel
                        val type = truckRestrictionWarning.dimensionRestriction!!.type
                        val value = truckRestrictionWarning.dimensionRestriction!!.valueInCentimeters
                        Log.d(TAG, "TruckRestriction for dimension: ${type.name}: $value")
                    }
                    else -> {
                        Log.d(TAG, "TruckRestriction: General restriction - no trucks allowed.")
                    }
                }
            }
        }
    }

    /**
     * Sets up low speed zone warning listener for the visual navigator
     *
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupLowSpeedZoneWarnings(visualNavigator: VisualNavigator) {
        // Notifies on low speed zones ahead - as indicated also on the map when MapFeatures.LOW_SPEED_ZONE is set
        visualNavigator.setLowSpeedZoneWarningListener { lowSpeedZoneWarning ->
            when (lowSpeedZoneWarning.distanceType) {
                DistanceType.AHEAD -> {
                    Log.d(TAG, "Low speed zone ahead in meters: ${lowSpeedZoneWarning.distanceToLowSpeedZoneInMeters}")
                    Log.d(TAG, "Speed limit in low speed zone (m/s): ${lowSpeedZoneWarning.speedLimitInMetersPerSecond}")
                }
                DistanceType.REACHED -> {
                    Log.d(TAG, "A low speed zone has been reached.")
                    Log.d(TAG, "Speed limit in low speed zone (m/s): ${lowSpeedZoneWarning.speedLimitInMetersPerSecond}")
                }
                DistanceType.PASSED -> {
                    Log.d(TAG, "A low speed zone has been passed.")
                }
                else -> {} // Handle other cases if needed
            }
        }
    }

    /**
     * Sets up the road texts listener for the visual navigator
     * 
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupRoadTextsListener(visualNavigator: VisualNavigator) {
        // Notifies whenever any textual attribute of the current road changes
        // This can be useful during tracking mode, when no maneuver information is provided
        visualNavigator.setRoadTextsListener { roadTexts ->
            // Use the road texts to display current road information
            // Similar to how getRoadName() extracts information from the provided RoadTexts
            val roadName = roadTexts.names.defaultValue ?: roadTexts.numbersWithDirection.defaultValue ?: "unnamed road"
            Log.d(TAG, "Current road: $roadName")
        }
    }

    /**
     * Sets up realistic view warnings for the visual navigator
     * 
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupRealisticViewWarnings(visualNavigator: VisualNavigator) {
        // Configure realistic view options
        val realisticViewWarningOptions = RealisticViewWarningOptions().apply {
            aspectRatio = AspectRatio.ASPECT_RATIO_3_X_4
            darkTheme = false
        }
        visualNavigator.setRealisticViewWarningOptions(realisticViewWarningOptions)
        
        // Notifies on signposts together with complex junction views
        visualNavigator.setRealisticViewWarningListener { realisticViewWarning ->
            val distance = realisticViewWarning.distanceToRealisticViewInMeters
            val distanceType = realisticViewWarning.distanceType

            // Note that DistanceType.REACHED is not used for Signposts and junction views
            // as a junction is identified through a location instead of an area
            when (distanceType) {
                DistanceType.AHEAD -> {
                    Log.d(TAG, "A RealisticView ahead in: $distance meters.")
                }
                DistanceType.PASSED -> {
                    Log.d(TAG, "A RealisticView just passed.")
                }
                else -> {} // Handle other cases if needed
            }

            // Process the junction view SVG data, if available
            realisticViewWarning.realisticViewVectorImage?.let { realisticView ->
                val signpostSvgImageContent = realisticView.signpostSvgImageContent
                val junctionViewSvgImageContent = realisticView.junctionViewSvgImageContent
                
                // The resolution-independent SVG data can now be used to visualize the image
                // Use an SVG library to create an image from the SVG string
                // Both SVGs contain the same dimension and the signpost SVG should be shown on top
                if (signpostSvgImageContent != null) {
                    Log.d(TAG, "Received signpost SVG content")
                }
                if (junctionViewSvgImageContent != null) {
                    Log.d(TAG, "Received junction view SVG content")
                }
            } ?: run {
                Log.d(TAG, "A RealisticView passed but no SVG data was delivered.")
            }
        }
    }
    
    /**
     * Sets up toll stop warning listener for the visual navigator
     *
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupTollStopWarnings(visualNavigator: VisualNavigator) {
        // Notifies on upcoming toll stops
        // Uses the same notification thresholds as other warnings and provides events with or without a route to follow
        visualNavigator.setTollStopWarningListener { tollStop ->
            val lanes = tollStop.lanes
            
            // The lane at index 0 is the leftmost lane adjacent to the middle of the road
            // The lane at the last index is the rightmost lane
            lanes.forEachIndexed { laneNumber, tollBoothLane ->
                // Log which vehicle types are allowed on this lane that leads to the toll booth
                logLaneAccess(laneNumber, tollBoothLane.access)
                
                val tollBooth = tollBoothLane.booth
                val tollCollectionMethods = tollBooth.tollCollectionMethods
                val paymentMethods = tollBooth.paymentMethods
                
                // The supported collection methods like ticket or automatic/electronic
                for (collectionMethod in tollCollectionMethods) {
                    Log.d(TAG, "This toll stop supports collection via: ${collectionMethod.name}")
                }
                
                // The supported payment methods like cash or credit card
                for (paymentMethod in paymentMethods) {
                    Log.d(TAG, "This toll stop supports payment via: ${paymentMethod.name}")
                }
            }
        }
    }
    
    /**
     * Log lane recommendations for navigation assistance
     * 
     * @param lanes List of Lane objects to process
     */
    private fun logLaneRecommendations(lanes: List<Lane>) {
        // The lane at index 0 is the leftmost lane adjacent to the middle of the road
        // The lane at the last index is the rightmost lane
        lanes.forEachIndexed { laneNumber, lane ->
            // This state is only possible if maneuverViewLaneAssistance.lanesForNextNextManeuver is not empty
            // For example, when two lanes go left, this lanes leads only to the next maneuver,
            // but not to the maneuver after the next maneuver
            if (lane.recommendationState == LaneRecommendationState.RECOMMENDED) {
                Log.d(TAG, "Lane $laneNumber leads to next maneuver, but not to the next next maneuver.")
            }
            
            // If laneAssistance.lanesForNextNextManeuver is not empty, this lane leads also to the
            // maneuver after the next maneuver
            if (lane.recommendationState == LaneRecommendationState.HIGHLY_RECOMMENDED) {
                Log.d(TAG, "Lane $laneNumber leads to next maneuver and eventually to the next next maneuver.")
            }
            
            if (lane.recommendationState == LaneRecommendationState.NOT_RECOMMENDED) {
                Log.d(TAG, "Do not take lane $laneNumber to follow the route.")
            }
            
            logLaneDetails(laneNumber, lane)
        }
    }
    
    /**
     * Log detailed information about a lane
     * 
     * @param laneNumber The index of the lane
     * @param lane The Lane object to log information for
     */
    private fun logLaneDetails(laneNumber: Int, lane: Lane) {
        // All directions can be true or false at the same time
        // The possible lane directions are valid independent of a route
        // If a lane leads to multiple directions and is recommended, then all directions lead to
        // the next maneuver
        // You can use this information like in a bitmask to visualize the possible directions
        // with a set of image overlays
        lane.directionCategory.let { dc ->
            Log.d(TAG, "Directions for lane $laneNumber")
            Log.d(TAG, "laneDirectionCategory.straight: ${dc.straight}")
            Log.d(TAG, "laneDirectionCategory.slightlyLeft: ${dc.slightlyLeft}")
            Log.d(TAG, "laneDirectionCategory.quiteLeft: ${dc.quiteLeft}")
            Log.d(TAG, "laneDirectionCategory.hardLeft: ${dc.hardLeft}")
            Log.d(TAG, "laneDirectionCategory.uTurnLeft: ${dc.uTurnLeft}")
            Log.d(TAG, "laneDirectionCategory.slightlyRight: ${dc.slightlyRight}")
            Log.d(TAG, "laneDirectionCategory.quiteRight: ${dc.quiteRight}")
            Log.d(TAG, "laneDirectionCategory.hardRight: ${dc.hardRight}")
            Log.d(TAG, "laneDirectionCategory.uTurnRight: ${dc.uTurnRight}")
        }
        
        // More information on each lane is available in these bitmasks (boolean):
        // LaneType provides lane properties such as if parking is allowed
        val laneType = lane.type
        
        // LaneAccess provides which vehicle type(s) are allowed to access this lane
        logLaneAccess(laneNumber, lane.access)
        
        // LaneMarkings indicate the visual style of dividers between lanes as visible on a road
        logLaneMarkings(lane.laneMarkings)
    }
    
    /**
     * Log lane markings information
     * 
     * @param laneMarkings The LaneMarkings object to log information for
     */
    private fun logLaneMarkings(laneMarkings: LaneMarkings) {
        laneMarkings.centerDividerMarker?.let {
            // A CenterDividerMarker specifies the line type used for center dividers on bidirectional roads
            Log.d(TAG,"Center divider marker for lane ${it.value}")
        } ?: laneMarkings.laneDividerMarker?.let {
            // A LaneDividerMarker specifies the line type of driving lane separators present on a road
            // It indicates the lane separator on the right side of the
            // specified lane in the lane driving direction for right-side driving countries
            // For left-sided driving countries it indicates the
            // lane separator on the left side of the specified lane in the lane driving direction
            Log.d(TAG, "Lane divider marker for lane ${it.value}")
        }
    }
    
    /**
     * Log which vehicle types are allowed on a lane
     * 
     * @param laneNumber The index of the lane
     * @param laneAccess The LaneAccess object to log information for
     */
    private fun logLaneAccess(laneNumber: Int, laneAccess: LaneAccess) {
        Log.d(TAG, "Lane access for lane $laneNumber")
        Log.d(TAG, "Automobiles are allowed on this lane: ${laneAccess.automobiles}")
        Log.d(TAG, "Buses are allowed on this lane: ${laneAccess.buses}")
        Log.d(TAG, "Taxis are allowed on this lane: ${laneAccess.taxis}")
        Log.d(TAG, "Carpools are allowed on this lane: ${laneAccess.carpools}")
        Log.d(TAG, "Pedestrians are allowed on this lane: ${laneAccess.pedestrians}")
        Log.d(TAG, "Trucks are allowed on this lane: ${laneAccess.trucks}")
        Log.d(TAG, "ThroughTraffic is allowed on this lane: ${laneAccess.throughTraffic}")
        Log.d(TAG, "DeliveryVehicles are allowed on this lane: ${laneAccess.deliveryVehicles}")
        Log.d(TAG, "EmergencyVehicles are allowed on this lane: ${laneAccess.emergencyVehicles}")
        Log.d(TAG, "Motorcycles are allowed on this lane: ${laneAccess.motorcycles}")
    }
    
    /**
     * Extract road name from maneuver information
     * 
     * @param maneuver The maneuver containing road information
     * @return The road name to display
     */
    private fun getRoadName(maneuver: Maneuver): String {
        val currentRoadTexts = maneuver.roadTexts
        val nextRoadTexts = maneuver.nextRoadTexts
        
        val currentRoadName = currentRoadTexts.names.defaultValue
        val currentRoadNumber = currentRoadTexts.numbersWithDirection.defaultValue
        val nextRoadName = nextRoadTexts.names.defaultValue
        val nextRoadNumber = nextRoadTexts.numbersWithDirection.defaultValue
        
        var roadName = nextRoadName ?: nextRoadNumber
        
        // On highways, we want to show the highway number instead of a possible road name,
        // while for inner city and urban areas road names are preferred over road numbers
        if (maneuver.nextRoadType == RoadType.HIGHWAY) {
            roadName = nextRoadNumber ?: nextRoadName
        }
        
        if (maneuver.action == ManeuverAction.ARRIVE) {
            // We are approaching the destination, so there's no next road
            roadName = currentRoadName ?: currentRoadNumber
        }
        
        // Happens only in rare cases, when also the fallback is null
        return roadName ?: "unnamed road"
    }
    
    /**
     * Get the current effective speed limit
     * 
     * @param speedLimit The SpeedLimit object from HERE SDK
     * @return The effective speed limit in meters per second, or null if not available
     */
    private fun getCurrentSpeedLimit(speedLimit: SpeedLimit): Double? {
        // Note that all values can be null if no data is available
        // The regular speed limit if available. In case of unbounded speed limit, the value is zero.
        Log.d(TAG, "speedLimitInMetersPerSecond: ${speedLimit.speedLimitInMetersPerSecond}")
        // A conditional school zone speed limit as indicated on the local road signs
        Log.d(TAG, "schoolZoneSpeedLimitInMetersPerSecond: ${speedLimit.schoolZoneSpeedLimitInMetersPerSecond}")
        // A conditional time-dependent speed limit as indicated on the local road signs
        // It is in effect considering the current local time provided by the device's clock
        Log.d(TAG, "timeDependentSpeedLimitInMetersPerSecond: ${speedLimit.timeDependentSpeedLimitInMetersPerSecond}")
        // A conditional non-legal speed limit that recommends a lower speed,
        // for example, due to bad road conditions
        Log.d(TAG, "advisorySpeedLimitInMetersPerSecond: ${speedLimit.advisorySpeedLimitInMetersPerSecond}")
        // Weather-dependent speed limits as indicated on the local road signs
        // The HERE SDK cannot detect the current weather condition, so a driver must decide
        // based on the situation if these speed limits apply
        Log.d(TAG, "fogSpeedLimitInMetersPerSecond: ${speedLimit.fogSpeedLimitInMetersPerSecond}")
        Log.d(TAG, "rainSpeedLimitInMetersPerSecond: ${speedLimit.rainSpeedLimitInMetersPerSecond}")
        Log.d(TAG, "snowSpeedLimitInMetersPerSecond: ${speedLimit.snowSpeedLimitInMetersPerSecond}")
        
        // For convenience, this returns the effective (lowest) speed limit between
        // - speedLimitInMetersPerSecond
        // - schoolZoneSpeedLimitInMetersPerSecond
        // - timeDependentSpeedLimitInMetersPerSecond
        return speedLimit.effectiveSpeedLimitInMetersPerSecond()
    }
    
    /**
     * Gets ETA information from the route progress
     * 
     * @param routeProgress The current progress along the route
     * @return A string with the ETA information
     */
    private fun getETA(routeProgress: RouteProgress): String {
        val sectionProgressList = routeProgress.sectionProgress
        
        // sectionProgressList is guaranteed to be non-empty
        val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
        val currentETAString = "ETA: ${timeUtils.getETAinDeviceTimeZone(lastSectionProgress.remainingDuration.seconds.toInt())}"
        Log.d(TAG, "Distance to destination in meters: ${lastSectionProgress.remainingDistanceInMeters}")
        Log.d(TAG, "Traffic delay ahead in seconds: ${lastSectionProgress.trafficDelay.seconds}")
        // Logs current ETA
        Log.d(TAG, currentETAString)
        return currentETAString
    }
    
    /**
     * Sets up voice guidance in the user's preferred language
     * 
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupVoiceGuidance(visualNavigator: VisualNavigator) {
        val ttsLanguageCode = getLanguageCodeForDevice(VisualNavigator.getAvailableLanguagesForManeuverNotifications())
        val maneuverNotificationOptions = ManeuverNotificationOptions().apply {
            // Set the language in which the notifications will be generated
            language = ttsLanguageCode
            // Set the measurement system used for distances
            unitSystem = UnitSystem.METRIC
        }
        visualNavigator.setManeuverNotificationOptions(maneuverNotificationOptions)
        Log.d(TAG, "LanguageCode for maneuver notifications: $ttsLanguageCode")
        
        // Set language to our TextToSpeech engine
        val locale = LanguageCodeConverter.getLocale(ttsLanguageCode)
        if (voiceAssistant.setLanguage(locale)) {
            Log.d(TAG, "TextToSpeech engine uses this language: $locale")
        } else {
            Log.e(TAG, "TextToSpeech engine does not support this language: $locale")
        }
    }
    
    /**
     * Determines the preferred language for voice guidance based on device settings
     * 
     * @param supportedVoiceSkins List of languages supported by the HERE SDK
     * @return The selected LanguageCode for navigation
     */
    private fun getLanguageCodeForDevice(supportedVoiceSkins: List<LanguageCode>): LanguageCode {
        // 1. Determine if preferred device language is supported by our TextToSpeech engine
        var localeForCurrentDevice = Locale.getDefault()
        if (!voiceAssistant.isLanguageAvailable(localeForCurrentDevice)) {
            Log.e(TAG, "TextToSpeech engine does not support: $localeForCurrentDevice, falling back to EN_US.")
            localeForCurrentDevice = Locale("en", "US")
        }
        
        // 2. Determine supported voice skins from HERE SDK
        var languageCodeForCurrentDevice = LanguageCodeConverter.getLanguageCode(localeForCurrentDevice)
        if (!supportedVoiceSkins.contains(languageCodeForCurrentDevice)) {
            Log.e(TAG, "No voice skins available for $languageCodeForCurrentDevice, falling back to EN_US.")
            languageCodeForCurrentDevice = LanguageCode.EN_US
        }
        
        return languageCodeForCurrentDevice
    }
    
    /**
     * Sets up speed warnings for the visual navigator
     * 
     * @param visualNavigator The VisualNavigator to configure
     */
    private fun setupSpeedWarnings(visualNavigator: VisualNavigator) {
        val speedLimitOffset = SpeedLimitOffset().apply {
            lowSpeedOffsetInMetersPerSecond = 2.0
            highSpeedOffsetInMetersPerSecond = 4.0
            highSpeedBoundaryInMetersPerSecond = 25.0
        }
//        visualNavigator.setSpeedLimitOffset(speedLimitOffset)

    }
    
    /**
     * Periodically updates the traffic information for the current route.
     * This method checks whether the last traffic update occurred within the specified interval and skips the update if not.
     * Then it calculates the current traffic conditions along the route using the `RoutingEngine`.
     * Lastly, it updates the `VisualNavigator` with the newly calculated `TrafficOnRoute` object,
     * which affects the `RouteProgress` duration without altering the route geometry or distance.
     *
     * Note: This code initiates periodic calls to the HERE Routing backend. Depending on your contract,
     * each call may be charged separately. It's the application's responsibility to decide how and how
     * often this code should be executed.
     *
     * @param routeProgress The current progress along the route
     * @param visualNavigator The Visual Navigator instance to update with traffic information
     */
    private fun updateTrafficOnRoute(routeProgress: RouteProgress, visualNavigator: VisualNavigator) {
        val currentRoute = visualNavigator.route ?: return // Should never happen
        
        // Below, we use 10 minutes. A common range is between 5 and 15 minutes
        val trafficUpdateIntervalInMilliseconds = 10 * 60000L // 10 minutes
        val now = System.currentTimeMillis()
        
        if ((now - lastTrafficUpdateInMilliseconds) < trafficUpdateIntervalInMilliseconds) {
            return
        }
        
        // Store the current time when we update trafficOnRoute
        lastTrafficUpdateInMilliseconds = now
        
        val sectionProgressList = routeProgress.sectionProgress
        val lastSectionProgress = sectionProgressList[sectionProgressList.size - 1]
        val traveledDistanceOnLastSectionInMeters = currentRoute.lengthInMeters - lastSectionProgress.remainingDistanceInMeters
        val lastTraveledSectionIndex = routeProgress.sectionIndex
        
        routingEngine.calculateTrafficOnRoute(
                currentRoute,
                lastTraveledSectionIndex,
                traveledDistanceOnLastSectionInMeters
        ) { routingError, trafficOnRoute ->
            if (routingError != null) {
                Log.d(TAG, "CalculateTrafficOnRoute error: ${routingError.name}")
                return@calculateTrafficOnRoute
            }
            
            // Sets traffic data for the current route, affecting RouteProgress duration in SectionProgress,
            // while preserving route distance and geometry
            trafficOnRoute?.let { 
                visualNavigator.setTrafficOnRoute(it)
                Log.d(TAG, "Updated traffic on route.")
            }
        }
    }
}
