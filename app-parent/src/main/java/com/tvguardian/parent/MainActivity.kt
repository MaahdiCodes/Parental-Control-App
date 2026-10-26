package com.tvguardian.parent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.google.firebase.auth.FirebaseAuth
import com.tvguardian.parent.auth.AuthRepository
import com.tvguardian.parent.auth.SignInScreen
import com.tvguardian.parent.devices.DevicesScreen
import com.tvguardian.parent.ui.ParentTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParentTheme {
                val user by AuthRepository.user.collectAsState(initial = FirebaseAuth.getInstance().currentUser)
                val scope = rememberCoroutineScope()
                val signedInUser = user
                if (signedInUser == null) {
                    SignInScreen()
                } else {
                    DevicesScreen(
                        user = signedInUser,
                        onSignOut = { scope.launch { AuthRepository.signOut(this@MainActivity) } },
                    )
                }
            }
        }
    }
}
