package com.archiverclient.companion.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.archiverclient.companion.data.Settings
import com.archiverclient.companion.media.MediaAccess
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) { CompanionScreen() }
            }
        }
    }
}

@Composable
private fun CompanionScreen(model: CompanionViewModel = viewModel()) {
    val context = LocalContext.current
    val peers by model.peers.collectAsState()
    val indexed by model.indexed.collectAsState()
    val hashed by model.hashed.collectAsState()
    val pairing by model.pairing.collectAsState()

    var access by remember { mutableStateOf(MediaAccess.of(context)) }
    var scanning by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }

    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Re-read rather than trusting the result map: a partial grant on Android 14+
        // comes back as a refusal of the two permissions we asked for, which is not
        // the same thing as having no access at all.
        access = MediaAccess.of(context)
    }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> scanning = ok }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Archiver Companion", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This phone offers its photos and videos to the computers you pair it with. " +
                "They come and take copies; nothing is uploaded anywhere else.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!access.usable) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Photos and videos", style = MaterialTheme.typography.titleMedium)
                    if (access == MediaAccess.Partial) {
                        Text(
                            "Only a few selected photos are shared with this app, so only " +
                                "those would ever reach your computer — and anything you " +
                                "later deselect would be dropped from the archive. This app " +
                                "needs access to all photos.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = {
                            context.startActivity(
                                Intent(
                                    AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"),
                                )
                            )
                        }) { Text("Open permissions") }
                    } else {
                        Text(
                            "The app needs to read your camera roll to be able to list it. " +
                                "Location access is only used so the copies keep the place " +
                                "they were taken.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { ask.launch(MediaAccess.request()) }) { Text("Allow") }
                    }
                }
            }
            return@Column
        }

        if (scanning) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Point at the code on the computer")
                    QrScanner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                    ) { raw ->
                        scanning = false
                        model.pair(raw)
                    }
                    TextButton(onClick = { scanning = false }) { Text("Cancel") }
                }
            }
        }

        pairing.message?.let { message ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message)
                    TextButton(onClick = { model.clearPairingMessage() }) { Text("OK") }
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Computers", style = MaterialTheme.typography.titleMedium)
                if (peers.isEmpty()) {
                    Text(
                        "None yet. On the computer, add a phone source and choose " +
                            "\"Sign in again\" to show a code.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    peers.forEach { peer ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(peer.displayName, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    when {
                                        peer.revoked ->
                                            "No longer accepts this phone — pair again"
                                        // Not yet believed: see HeartbeatPolicy.refused.
                                        peer.refusals > 0 ->
                                            "Turning this phone away since " +
                                                when_(peer.refusedSince)
                                        peer.lastHelloAt == 0L -> "Not announced yet"
                                        peer.lastHelloOk ->
                                            "Last seen ${when_(peer.lastHelloAt)} at " +
                                                "${peer.lastAddress}:${peer.lastPort}"
                                        else -> "Last tried ${when_(peer.lastHelloAt)}, no answer"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(onClick = { model.unpair(peer) }) { Text("Unpair") }
                        }
                    }
                }
                Button(onClick = { camera.launch(Manifest.permission.CAMERA) }) {
                    Text(if (peers.isEmpty()) "Pair with a computer" else "Pair another")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This phone", style = MaterialTheme.typography.titleMedium)
                Text("Name: ${model.deviceName}", style = MaterialTheme.typography.bodySmall)
                Text("Indexed: $indexed photos and videos", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Checksummed: $hashed of $indexed",
                    style = MaterialTheme.typography.bodySmall,
                )
                val port = Settings(context).serverPort
                Text(
                    if (port > 0) "Serving on port $port" else "Server not started",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedButton(onClick = { model.indexNow() }) { Text("Index now") }
            }
        }

        BatteryCard()

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Paste a code", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If the camera will not read it, the computer's code can be typed in.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text("ac://pair?...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = typed.isNotBlank() && !pairing.busy,
                    onClick = {
                        model.pair(typed.trim())
                        typed = ""
                    },
                ) { Text("Pair") }
            }
        }
    }
}

/**
 * The manufacturers' battery managers are the single most common reason an app
 * like this stops working overnight, and no amount of correct Android code gets
 * round them. Saying so is more use than failing quietly.
 */
@Composable
private fun BatteryCard() {
    val context = LocalContext.current
    val power = context.getSystemService(PowerManager::class.java)
    val exempt = power?.isIgnoringBatteryOptimizations(context.packageName) == true
    if (exempt) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Staying reachable", style = MaterialTheme.typography.titleMedium)
            Text(
                "Android may stop this app in the background, and your computer can only " +
                    "collect photos while it is running. Allowing it to run unrestricted " +
                    "fixes that.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                runCatching {
                    context.startActivity(
                        Intent(
                            AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:${context.packageName}"),
                        )
                    )
                }.onFailure {
                    context.startActivity(
                        Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    )
                }
            }) { Text("Allow background use") }
        }
    }
}

private fun when_(at: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
