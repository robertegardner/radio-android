package io.rg2.radio.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.Text
import io.rg2.radio.auth.AuthStatus
import io.rg2.radio.auth.DeviceCodeLogin
import io.rg2.radio.auth.DeviceLoginState
import io.rg2.radio.wear.WearApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WearAccountScreen(onDone: () -> Unit) {
    val container = (LocalContext.current.applicationContext as WearApp).container
    val status by container.auth.status.collectAsState()
    val login = remember { DeviceCodeLogin(container.oauth, container.auth) }
    val dl by login.state.collectAsState()
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when {
            dl is DeviceLoginState.ShowCode -> {
                val s = dl as DeviceLoginState.ShowCode
                Text("Go to", textAlign = TextAlign.Center)
                Text("authentik.bobgardner.org/device", textAlign = TextAlign.Center)
                Text(s.userCode, textAlign = TextAlign.Center)
            }
            status is AuthStatus.SignedIn -> {
                Text("Signed in as ${(status as AuthStatus.SignedIn).username}", textAlign = TextAlign.Center)
                Chip(label = { Text("Sign out") }, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { container.auth.signOut() } }
                })
                Chip(label = { Text("Back") }, onClick = onDone)
            }
            else -> {
                (dl as? DeviceLoginState.Failed)?.let { Text(it.message, textAlign = TextAlign.Center) }
                Chip(label = { Text("Sign in") }, onClick = {
                    scope.launch { withContext(Dispatchers.IO) { login.run() } }
                })
                Chip(label = { Text("Back") }, onClick = onDone)
            }
        }
    }
}
