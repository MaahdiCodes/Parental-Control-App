package com.tvguardian.parent.devices

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseUser
import com.tvguardian.core.DeviceStatus
import com.tvguardian.parent.pairing.AddTvDialog
import com.tvguardian.parent.watch.DeviceScreen
import com.tvguardian.parent.youtube.YouTubeLinkService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val OnlineColor = Color(0xFF16A34A)
private val OfflineColor = Color(0xFF9CA3AF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(user: FirebaseUser, onSignOut: () -> Unit) {
    val familyId = user.uid
    val result by remember(familyId) { DevicesRepository.devices(familyId) }.collectAsState(initial = null)
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var showAddTv by remember { mutableStateOf(false) }
    var openDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var managing by remember { mutableStateOf<TvDevice?>(null) }

    // Re-evaluate online/offline without waiting for a database change.
    LaunchedEffect(Unit) {
        while (true) {
            delay(15_000)
            now = System.currentTimeMillis()
        }
    }

    // TVs linked through YouTube (e.g. LG) are watched by a background service on this phone.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val hasYouTubeLinkedTv = result?.getOrNull()?.any { it.isYouTubeLink } == true
    LaunchedEffect(hasYouTubeLinkedTv) {
        if (!hasYouTubeLinkedTv) return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        YouTubeLinkService.start(context)
    }

    managing?.let { DeviceDialog(familyId, it, onDismiss = { managing = null }) }

    // One TV's detail screen (a removed TV drops back to the list automatically).
    val openDevice = result?.getOrNull()?.find { it.id == openDeviceId }
    if (openDevice != null) {
        BackHandler { openDeviceId = null }
        DeviceScreen(
            familyId = familyId,
            device = openDevice,
            now = now,
            onBack = { openDeviceId = null },
            onManage = { managing = openDevice },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TV Guardian") },
                actions = { TextButton(onClick = onSignOut) { Text("Sign out") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddTv = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add TV") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Signed in as ${user.email}", style = MaterialTheme.typography.bodySmall)
            val current = result
            when {
                current == null -> CircularProgressIndicator()
                current.isFailure -> Text(
                    "Couldn't load your TVs: ${current.exceptionOrNull()?.message}",
                    color = MaterialTheme.colorScheme.error,
                )
                current.getOrThrow().isEmpty() -> Text(
                    "No TVs yet. Open TV Guardian on the TV, then tap \"Add TV\" and scan the code it shows."
                )
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(current.getOrThrow(), key = { it.id }) { device ->
                        DeviceCard(device, now, onClick = { openDeviceId = device.id })
                    }
                }
            }
        }
    }

    if (showAddTv) AddTvDialog(familyId, onDismiss = { showAddTv = false })
}

@Composable
private fun DeviceCard(device: TvDevice, now: Long, onClick: () -> Unit) {
    val online = DeviceStatus.isOnline(device.lastSeenMillis, now)
    Card(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(12.dp)
                    .background(if (online) OnlineColor else OfflineColor, CircleShape)
            )
            Column(Modifier.padding(start = 16.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                val lastSeen = DeviceStatus.describeLastSeen(device.lastSeenMillis, now)
                Text(
                    when {
                        device.isYouTubeLink && online -> "YouTube open"
                        device.isYouTubeLink -> "YouTube closed · last open $lastSeen"
                        online -> "Online"
                        else -> "Offline · last seen $lastSeen"
                    },
                    color = if (online) OnlineColor else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (device.isYouTubeLink) {
                    Text("Linked through YouTube", style = MaterialTheme.typography.bodySmall)
                }
                if (device.youtubeBlocked) {
                    Text("🚫 YouTube blocked", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                device.model?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                val missing = listOfNotNull(
                    "Accessibility".takeIf { device.accessibilityOn == false },
                    "Notification access".takeIf { device.notificationAccessOn == false },
                )
                if (missing.isNotEmpty()) {
                    Text(
                        "⚠ Protection off on TV: ${missing.joinToString()}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceDialog(familyId: String, device: TvDevice, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(device.name) }
    var confirmRemove by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(device.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                if (confirmRemove) {
                    Text("Remove this TV? It will go back to showing a pairing code.", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = {
                    if (!confirmRemove) {
                        confirmRemove = true
                    } else {
                        scope.launch {
                            runCatching { DevicesRepository.remove(familyId, device.id) }
                                .onSuccess { onDismiss() }
                                .onFailure { error = it.message }
                        }
                    }
                }) {
                    Text(if (confirmRemove) "Yes, remove this TV" else "Remove TV", color = MaterialTheme.colorScheme.error)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(
                enabled = name.isNotBlank(),
                onClick = {
                    scope.launch {
                        runCatching { DevicesRepository.rename(familyId, device.id, name.trim()) }
                            .onSuccess { onDismiss() }
                            .onFailure { error = it.message }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
