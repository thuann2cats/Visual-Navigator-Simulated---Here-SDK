package com.example.navigation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.navigation.ui.theme.NavigationTheme
import com.here.sdk.consent.Consent
import com.here.sdk.consent.ConsentEngine
import com.here.sdk.core.errors.InstantiationErrorException

/**
 * Required by HERE positioning.
 * Shows what answer the user has given regarding the consent to join the data improvement
 * program, and allows them to change it by showing a consent dialog.
 */
class ConsentStateActivity : ComponentActivity() {
    
    private lateinit var consentEngine: ConsentEngine
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            consentEngine = ConsentEngine()
        } catch (e: InstantiationErrorException) {
            throw RuntimeException("Initialization of ConsentEngine failed: ${e.message}")
        }
        
        setContent {
            NavigationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConsentScreen()
                }
            }
        }
    }
    
    @Composable
    fun ConsentScreen() {
        var consentState by remember { mutableStateOf(getConsentStateText()) }
        
        // Update consent state when screen is first displayed
        LaunchedEffect(Unit) {
            consentState = getConsentStateText()
        }
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "HERE SDK Consent State",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            
            Text(
                text = consentState,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(bottom = 32.dp)
            )
            
            Button(
                onClick = {
                    consentEngine.requestUserConsent()
                }
            ) {
                Text("Change Consent Status")
            }
        }
    }
    
    private fun getConsentStateText(): String {
        return when (consentEngine.userConsentState) {
            Consent.UserReply.GRANTED -> getString(R.string.consent_state_granted)
            Consent.UserReply.DENIED,
            Consent.UserReply.NOT_HANDLED,
            Consent.UserReply.REQUESTING -> getString(R.string.consent_state_denied)
            else -> "Unknown user consent state"
        }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh the UI to show the current consent state
        setContent {
            NavigationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ConsentScreen()
                }
            }
        }
    }
}
