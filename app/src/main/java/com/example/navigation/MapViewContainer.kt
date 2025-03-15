package com.example.navigation

import android.os.Bundle
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.navigation.viewmodel.NavigationViewModel
import com.here.sdk.mapview.MapFeatureModes
import com.here.sdk.mapview.MapFeatures
import com.here.sdk.mapview.MapScene
import com.here.sdk.mapview.MapScheme
import com.here.sdk.mapview.MapView

@Composable
fun MapViewContainer(
    viewModel: NavigationViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var mapView: MapView? by remember { mutableStateOf(null) }
    
    // Collect dialog state from App
    val dialogState by viewModel.dialogState.collectAsState()
    
    DisposableEffect(Unit) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView?.onPause()
                Lifecycle.Event.ON_DESTROY -> mapView?.onDestroy()
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView = null
        }
    }
    
    Box(modifier = modifier) {
        AndroidView(
            factory = { ctx ->
                MapView(ctx).apply {
                    mapView = this
                    onCreate(Bundle())
                    
                    // Load the map scene
                    mapScene.loadScene(MapScheme.NORMAL_DAY) { mapError ->
                        if (mapError == null) {
                            // Initialize the navigation app after the map is loaded
                            viewModel.initializeApp(this, context)
                            
                            // Enable traffic flows and 3D landmarks by default
                            val mapFeatures = mapOf(
                                MapFeatures.TRAFFIC_FLOW to MapFeatureModes.TRAFFIC_FLOW_WITH_FREE_FLOW,
                                MapFeatures.LOW_SPEED_ZONES to MapFeatureModes.LOW_SPEED_ZONES_ALL,
                                MapFeatures.LANDMARKS to MapFeatureModes.LANDMARKS_TEXTURED
                            )
                            mapScene.enableFeatures(mapFeatures)
                        }
                    }
                }
            },
            modifier = modifier,
            update = { view ->
                // This block will be called when the composable is recomposed
                mapView = view
            }
        )
        
        // Show appropriate dialog based on the state
        when (val state = dialogState) {
            is App.DialogState.ErrorDialog -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDialog() },
                    title = { Text(state.title) },
                    text = { Text(state.message) },
                    confirmButton = {
                        TextButton(onClick = { viewModel.dismissDialog() }) {
                            Text("OK")
                        }
                    }
                )
            }
            is App.DialogState.NavigationDialog -> {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissDialog() },
                    title = { Text(state.title) },
                    text = { Text(state.message) },
                    confirmButton = {
                        Button(
                            onClick = { 
                                viewModel.startNavigation(state.route, state.isSimulated) 
                            }
                        ) {
                            Text(state.buttonText)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissDialog() }) {
                            Text("Cancel")
                        }
                    }
                )
            }
            null -> {
                // No dialog to show
            }
        }
    }
}
