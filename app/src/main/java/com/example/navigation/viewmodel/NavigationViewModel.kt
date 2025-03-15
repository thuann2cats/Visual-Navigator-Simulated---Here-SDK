package com.example.navigation.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.navigation.App
import com.here.sdk.mapview.MapView
import com.here.sdk.routing.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class NavigationViewModel : ViewModel() {
    
    private val _messageText = MutableStateFlow("Long press to set start/destination or use random ones.")
    val messageText: StateFlow<String> = _messageText.asStateFlow()
    
    private val _isCameraTrackingEnabled = MutableStateFlow(true)
    val isCameraTrackingEnabled: StateFlow<Boolean> = _isCameraTrackingEnabled.asStateFlow()
    
    private val _dialogState = MutableStateFlow<App.DialogState?>(null)
    val dialogState: StateFlow<App.DialogState?> = _dialogState
    
    private var app: App? = null
    
    // A simple message handler for the App to use
    // Modified to return Unit instead of the Job from viewModelScope.launch
    private val messageHandler: (String) -> Unit = { message ->
        viewModelScope.launch {
            _messageText.value = message
        }
        // No return value makes this lambda return Unit
    }
    
    fun initializeApp(mapView: MapView, context: Context) {
        // Initialize the App with our messageHandler lambda instead of a TextView
        app = App(context, mapView, messageHandler)
        
        // Observe dialog state from App
        viewModelScope.launch {
            app?.showDialogState?.collect { dialogState ->
                _dialogState.value = dialogState
            }
        }
    }
    
    fun addRouteSimulatedLocation() {
        app?.addRouteSimulatedLocation()
    }
    
    fun addRouteDeviceLocation() {
        app?.addRouteDeviceLocation()
    }
    
    fun clearMap() {
        app?.clearMapButtonPressed()
    }
    
    fun enableCameraTracking() {
        _isCameraTrackingEnabled.value = true
        app?.toggleTrackingButtonOnClicked()
    }
    
    fun disableCameraTracking() {
        _isCameraTrackingEnabled.value = false
        app?.toggleTrackingButtonOffClicked()
    }
    
    fun dismissDialog() {
        app?.dismissDialog()
    }
    
    fun startNavigation(route: Route, isSimulated: Boolean) {
        app?.startNavigation(route, isSimulated)
    }
    
    override fun onCleared() {
        app?.detach()
        super.onCleared()
    }
}
