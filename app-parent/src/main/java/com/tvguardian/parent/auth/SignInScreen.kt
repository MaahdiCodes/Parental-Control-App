package com.tvguardian.parent.auth

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import kotlinx.coroutines.launch

@Composable
fun SignInScreen() {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("TV Guardian", style = MaterialTheme.typography.headlineLarge)
            Text(
                "See and control what's playing on your family's TVs.\n" +
                    "Sign in with your Google account. It doesn't need to be the account on the TV.",
                textAlign = TextAlign.Center,
            )
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        error = null
                        try {
                            AuthRepository.signInWithGoogle(activity)
                        } catch (e: GetCredentialCancellationException) {
                            // User closed the sign-in sheet.
                        } catch (e: NoCredentialException) {
                            error = "No Google account found on this phone. Add one in Settings → Accounts."
                        } catch (e: Exception) {
                            error = "Sign-in failed: ${e.message}"
                        }
                        busy = false
                    }
                },
            ) {
                Text(if (busy) "Signing in…" else "Sign in with Google")
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center) }
        }
    }
}
