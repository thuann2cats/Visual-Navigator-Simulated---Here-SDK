package com.example.navigation

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.here.sdk.core.engine.AuthenticationMode
import com.here.sdk.core.engine.SDKNativeEngine
import com.here.sdk.core.engine.SDKOptions
import com.here.sdk.core.errors.InstantiationErrorException
import com.here.sdk.mapview.MapError
import com.here.sdk.mapview.MapFeatureModes
import com.here.sdk.mapview.MapFeatures
import com.here.sdk.mapview.MapScheme
import com.example.navigation.ui.theme.NavigationTheme
import com.example.navigation.viewmodel.NavigationViewModel

class MainActivity : ComponentActivity() {
    
    private val TAG = MainActivity::class.java.simpleName
    private lateinit var permissionsRequestor: PermissionsRequestor
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Initialize the HERE SDK
        initializeHERESDK()
        
        // Keep the screen alive for navigation
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        setContent {
            NavigationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onAboutClick = { navigateToConsentActivity() }
                    )
                }
            }
        }
        
        // Handle Android permissions
        handleAndroidPermissions()
    }
    
    private fun initializeHERESDK() {
        // Using BuildConfig to get credentials from build.gradle.kts
        val accessKeyID = BuildConfig.HERE_ACCESS_KEY_ID
        val accessKeySecret = BuildConfig.HERE_ACCESS_KEY_SECRET
        
        val authenticationMode = AuthenticationMode.withKeySecret(accessKeyID, accessKeySecret)
        val options = SDKOptions(authenticationMode)
        
        try {
            val context = this
            SDKNativeEngine.makeSharedInstance(context, options)
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of HERE SDK failed: ${e.error.name}")
        }
    }
    
    private fun handleAndroidPermissions() {
        permissionsRequestor = PermissionsRequestor(this)
        permissionsRequestor.request(object : PermissionsRequestor.ResultListener {
            override fun permissionsGranted() {
                Log.d(TAG, "Permissions granted")
            }
            
            override fun permissionsDenied() {
                Log.e(TAG, "Permissions denied by user")
            }
        })
    }
    
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionsRequestor.onRequestPermissionsResult(requestCode, grantResults)
    }
    
    private fun navigateToConsentActivity() {
        val intent = Intent(this, ConsentStateActivity::class.java)
        startActivity(intent)
    }
    
    override fun onPause() {
        super.onPause()
    }
    
    override fun onResume() {
        super.onResume()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disposeHERESDK()
    }
    
    private fun disposeHERESDK() {
        // Free HERE SDK resources before the application shuts down
        val sdkNativeEngine = SDKNativeEngine.getSharedInstance()
        sdkNativeEngine?.let {
            it.dispose()
            // For safety, explicitly set shared instance to null
            SDKNativeEngine.setSharedInstance(null)
        }
    }
}

@Composable
fun MainScreen(
    viewModel: NavigationViewModel = viewModel(),
    onAboutClick: () -> Unit
) {
    val context = LocalContext.current
    val messageText by viewModel.messageText.collectAsState()
    val isCameraTrackingEnabled by viewModel.isCameraTrackingEnabled.collectAsState()
    
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top app bar with about menu
        TopAppBar(
            title = { Text("HERE SDK Navigation") },
            actions = {
                IconButton(onClick = onAboutClick) {
                    Text("About")
                }
            }
        )
        
        // MapView container
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            MapViewContainer(
                viewModel = viewModel,
                modifier = Modifier.matchParentSize()
            )
        }
        
        // Message display area
        Text(
            text = messageText,
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth()
                .height(80.dp),
            textAlign = TextAlign.Center
        )
        
        // Navigation control buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(
                onClick = { viewModel.addRouteSimulatedLocation() },
                modifier = Modifier.weight(1f).padding(4.dp)
            ) {
                Text("Simulated")
            }
            
            Button(
                onClick = { viewModel.addRouteDeviceLocation() },
                modifier = Modifier.weight(1f).padding(4.dp)
            ) {
                Text("Device Location")
            }
            
            Button(
                onClick = { viewModel.clearMap() },
                modifier = Modifier.weight(1f).padding(4.dp)
            ) {
                Text("Clear")
            }
        }
        
        // Camera tracking toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp, start = 16.dp, end = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Camera Tracking: ")
            Switch(
                checked = isCameraTrackingEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        viewModel.enableCameraTracking()
                    } else {
                        viewModel.disableCameraTracking()
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopAppBar(
    title: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    SmallTopAppBar(
        title = title,
        actions = actions,
        colors = TopAppBarDefaults.smallTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.primary,
            titleContentColor = MaterialTheme.colorScheme.onPrimary,
            actionIconContentColor = MaterialTheme.colorScheme.onPrimary
        )
    )
}
