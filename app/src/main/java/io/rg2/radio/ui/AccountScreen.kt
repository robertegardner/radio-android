package io.rg2.radio.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.rg2.radio.RadioApp
import io.rg2.radio.auth.AuthRedirectActivity
import io.rg2.radio.auth.AuthStatus
import io.rg2.radio.auth.Pkce
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AccountRoute(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = (context.applicationContext as RadioApp).container
    val status by container.auth.status.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("ACCOUNT", style = MaterialTheme.typography.titleMedium)
        Text(
            when (val s = status) {
                AuthStatus.SignedOut -> "Not signed in — works at home; sign in to listen away from home."
                AuthStatus.Expired -> "Session expired — sign in again."
                is AuthStatus.SignedIn -> "Signed in as ${s.username} (${if (s.isAdmin) "admin" else "family"})"
            },
        )
        if (status is AuthStatus.SignedIn) {
            OutlinedButton(onClick = {
                scope.launch { withContext(Dispatchers.IO) { container.auth.signOut() } }
            }) { Text("Sign out") }
        } else {
            Button(onClick = { startSignIn(context, container.authConfig) }) { Text("Sign in") }
        }
    }
}

private fun startSignIn(context: Context, config: io.rg2.radio.auth.AuthConfig) {
    val verifier = Pkce.newVerifier()
    val state = Pkce.newState()
    context.getSharedPreferences(AuthRedirectActivity.PENDING_PREFS, Context.MODE_PRIVATE).edit()
        .putString("pkce_verifier", verifier).putString("pkce_state", state).commit()
    context.startActivity(
        Intent(Intent.ACTION_VIEW, Uri.parse(Pkce.authorizeUrl(config, Pkce.challenge(verifier), state)))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
