package com.example.navigation

import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.routing.CalculateRouteCallback
import com.here.sdk.routing.CarOptions
import com.here.sdk.routing.RoutingEngine
import com.here.sdk.routing.Waypoint

/**
 * A class that creates car Routes with the HERE SDK.
 */
class RouteCalculator {
    
    private val routingEngine: RoutingEngine
    
    init {
        try {
            routingEngine = RoutingEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of RoutingEngine failed: ${e.error.name}")
        }
    }
    
    fun calculateRoute(
        startWaypoint: Waypoint,
        destinationWaypoint: Waypoint,
        calculateRouteCallback: CalculateRouteCallback
    ) {
        val waypoints = listOf(startWaypoint, destinationWaypoint)
        
        // A route handle is required for the DynamicRoutingEngine to get updates on traffic-optimized routes
        val routingOptions = CarOptions().apply {
            routeOptions.enableRouteHandle = true
        }
        
        routingEngine.calculateRoute(
            waypoints,
            routingOptions,
            calculateRouteCallback
        )
    }
}
