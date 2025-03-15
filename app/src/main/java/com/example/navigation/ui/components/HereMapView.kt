package com.example.navigation.ui.components

import android.os.Bundle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.here.sdk.mapview.MapView

/**
 * A Jetpack Compose wrapper for the HERE SDK MapView.
 * This component handles lifecycle events automatically.
 *
 * @param modifier Modifier to be applied to the MapView
 * @param savedInstanceState Bundle to restore the map state from, if available
 * @param onMapViewReady Callback that provides access to the initialized MapView
 */
@Composable
fun HereMapView(
    modifier: Modifier = Modifier,
    savedInstanceState: Bundle? = null,
    onMapViewReady: (MapView) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // Create and remember the MapView
    val mapView = remember {
        MapView(context).apply {
            onCreate(savedInstanceState)
        }
    }
    
    // Call the callback to provide the MapView reference
    DisposableEffect(mapView) {
        onMapViewReady(mapView)
        
        onDispose { }
    }
    
    // Handle lifecycle events for the MapView
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> { /* no-op */ }
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    // Render the MapView in Compose
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { /* Updates can be handled here if needed */ }
    )
}
