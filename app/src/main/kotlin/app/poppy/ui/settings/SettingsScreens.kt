package app.poppy.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.poppy.BuildConfig
import app.poppy.R
import app.poppy.core.MatrixSession
import app.poppy.core.ThemeMode
import app.poppy.core.VerificationStep
import app.poppy.graph
import app.poppy.push.PushStatus
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.ui.details.TextPrompt
import app.poppy.util.copyToClipboard
import app.poppy.util.errorMessage
import app.poppy.util.shareLogs
import app.poppy.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.EnableRecoveryProgress
import org.matrix.rustcomponents.sdk.EnableRecoveryProgressListener
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.sdkGitSha

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(title: String, nav: Navigator, content: @Composable () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(title = { Text(title) }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) { content() }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
fun SettingsScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val profile by session.profile.collectAsState()
    val recovery by session.recoveryState.collectAsState()
    var editName by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }

    val pickAvatar = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { it.readBytes() } }
                session.client.uploadAvatar(mime, bytes)
                session.refreshProfile()
            }.onFailure { context.toast(errorMessage(it)) }
        }
    }

    SettingsScaffold("Settings", nav) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(
                profile.avatarUrl, profile.displayName ?: profile.userId, profile.userId, 96.dp,
                Modifier.clickable { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            )
            TextButton(onClick = { pickAvatar.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                Icon(Icons.Filled.CameraAlt, null, Modifier.size(16.dp))
                Text("  Change photo")
            }
            Text(
                profile.displayName ?: profile.userId,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.clickable { editName = true },
            )
            Text(profile.userId, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Security & encryption") },
            supportingContent = { Text("Verification, recovery key, devices") },
            leadingContent = { Icon(Icons.Filled.Security, null) },
            modifier = Modifier.clickable { nav.push(Screen.Security) },
        )
        ListItem(
            headlineContent = { Text("Notifications") },
            supportingContent = { Text("UnifiedPush, defaults, mentions") },
            leadingContent = { Icon(Icons.Filled.Notifications, null) },
            modifier = Modifier.clickable { nav.push(Screen.Notifications) },
        )
        ListItem(
            headlineContent = { Text("Appearance & privacy") },
            supportingContent = { Text("Theme, colours, typing and read receipts") },
            leadingContent = { Icon(Icons.Filled.Palette, null) },
            modifier = Modifier.clickable { nav.push(Screen.Appearance) },
        )
        ListItem(
            headlineContent = { Text("About Poppy") },
            leadingContent = { Icon(Icons.Filled.Info, null) },
            modifier = Modifier.clickable { nav.push(Screen.About) },
        )
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Sign out", color = MaterialTheme.colorScheme.error) },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { confirmLogout = true },
        )
    }

    if (editName) {
        TextPrompt("Display name", "Name", "", profile.displayName ?: "", onDismiss = { editName = false }) { name ->
            scope.launch {
                runCatching { session.client.setDisplayName(name.trim()) ; session.refreshProfile() }.onFailure { context.toast(errorMessage(it)) }
            }
        }
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { if (!loggingOut) confirmLogout = false },
            title = { Text("Sign out?") },
            text = {
                Text(
                    if (recovery == RecoveryState.ENABLED) "Your encrypted messages are backed up with your recovery key."
                    else "You haven't set up recovery. If this is your only device you will lose access to your encrypted messages.",
                )
            },
            confirmButton = {
                TextButton(enabled = !loggingOut, onClick = {
                    loggingOut = true
                    scope.launch {
                        context.graph.push.disable()
                        context.graph.notifications.clearAll()
                        context.graph.matrix.logout()
                    }
                }) {
                    if (loggingOut) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Sign out", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(enabled = !loggingOut, onClick = { confirmLogout = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SecurityScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val verification by session.verificationState.collectAsState()
    val recovery by session.recoveryState.collectAsState()
    var newKey by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var enterKey by remember { mutableStateOf(false) }
    var fingerprint by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { fingerprint = runCatching { session.client.encryption().ed25519Key() }.getOrNull() }

    fun setupRecovery() {
        progress = "Starting…"
        scope.launch {
            try {
                val key = session.client.encryption().enableRecovery(false, null, object : EnableRecoveryProgressListener {
                    override fun onUpdate(status: EnableRecoveryProgress) {
                        progress = when (status) {
                            is EnableRecoveryProgress.BackingUp -> "Backing up keys ${status.backedUpCount}/${status.totalCount}"
                            is EnableRecoveryProgress.CreatingBackup -> "Creating backup…"
                            is EnableRecoveryProgress.CreatingRecoveryKey -> "Creating recovery key…"
                            is EnableRecoveryProgress.Done -> null
                            is EnableRecoveryProgress.RoomKeyUploadError -> "Some keys couldn't be uploaded"
                            is EnableRecoveryProgress.Starting -> "Starting…"
                        }
                    }
                })
                newKey = key
            } catch (t: Throwable) {
                context.toast(errorMessage(t))
            } finally {
                progress = null
            }
        }
    }

    SettingsScaffold("Security & encryption", nav) {
        Section("This device")
        ListItem(
            headlineContent = { Text(if (verification == VerificationState.VERIFIED) "Verified" else "Not verified") },
            supportingContent = {
                Text(
                    if (verification == VerificationState.VERIFIED) "This device can read your encrypted messages and is trusted by your other devices."
                    else "Verify this device with another one you're signed in on, or use your recovery key.",
                )
            },
            leadingContent = { Icon(Icons.Filled.VerifiedUser, null, tint = if (verification == VerificationState.VERIFIED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) },
        )
        if (verification != VerificationState.VERIFIED) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    session.verification.reset()
                    nav.push(Screen.Verification)
                }) { Text("Use another device") }
                OutlinedButton(onClick = { enterKey = true }) { Text("Use recovery key") }
            }
        }
        ListItem(headlineContent = { Text("Device ID") }, supportingContent = { Text(session.deviceId, fontFamily = FontFamily.Monospace) })
        ListItem(
            headlineContent = { Text("Your devices") },
            supportingContent = { Text("See where you're signed in, rename or sign out devices") },
            leadingContent = { Icon(Icons.Filled.Devices, null) },
            modifier = Modifier.clickable { nav.push(Screen.Devices) },
        )
        fingerprint?.let { fp ->
            ListItem(
                headlineContent = { Text("Session key") },
                supportingContent = { Text(fp.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 13.sp) },
                modifier = Modifier.clickable { context.copyToClipboard(fp); context.toast("Copied") },
            )
        }

        Section("Recovery")
        ListItem(
            headlineContent = {
                Text(
                    when (recovery) {
                        RecoveryState.ENABLED -> "Recovery is set up"
                        RecoveryState.DISABLED -> "Recovery is off"
                        RecoveryState.INCOMPLETE -> "Recovery needs your key"
                        RecoveryState.UNKNOWN -> "Checking…"
                    },
                )
            },
            supportingContent = { Text("Your recovery key restores your encrypted message history and identity on new devices.") },
            leadingContent = { Icon(Icons.Filled.Key, null) },
        )
        progress?.let {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text("  $it")
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (recovery) {
                RecoveryState.DISABLED -> Button(enabled = progress == null, onClick = ::setupRecovery) { Text("Set up recovery") }
                RecoveryState.INCOMPLETE -> Button(onClick = { enterKey = true }) { Text("Enter recovery key") }
                RecoveryState.ENABLED -> FilledTonalButton(onClick = {
                    scope.launch {
                        runCatching { newKey = session.client.encryption().resetRecoveryKey() }.onFailure { context.toast(errorMessage(it)) }
                    }
                }) { Text("Change recovery key") }
                RecoveryState.UNKNOWN -> Unit
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    newKey?.let { key ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Save your recovery key") },
            text = {
                Column {
                    Text("Store it somewhere safe, like a password manager. It's the only way to recover your messages if you lose all your devices.")
                    Card(Modifier.padding(top = 16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest)) {
                        SelectionContainer {
                            Text(key, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(16.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { newKey = null }) { Text("I've saved it") } },
            dismissButton = { TextButton(onClick = { context.copyToClipboard(key, "Recovery key"); context.toast("Copied") }) { Text("Copy") } },
        )
    }

    if (enterKey) {
        var key by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!busy) enterKey = false },
            title = { Text("Enter recovery key") },
            text = {
                Column {
                    OutlinedTextField(key, { key = it }, label = { Text("Recovery key or passphrase") }, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && key.isNotBlank(), onClick = {
                    busy = true
                    scope.launch {
                        try {
                            session.client.encryption().recover(key.trim())
                            enterKey = false
                            context.toast("Recovered! Your history will now decrypt.")
                        } catch (t: Throwable) {
                            error = "That key didn't work. ${errorMessage(t)}"
                        } finally {
                            busy = false
                        }
                    }
                }) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Recover") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { enterKey = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun VerificationScreen(session: MatrixSession, nav: Navigator) {
    val step by session.verification.step.collectAsState()
    SettingsScaffold("Verify device", nav) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            when (val s = step) {
                VerificationStep.Idle -> {
                    Image(painterResource(R.drawable.ic_poppy), null, Modifier.size(96.dp))
                    Text("Confirm it's you", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Open another device where you're signed in. Poppy will send it a verification request and you'll compare a set of emoji.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Button(onClick = { session.verification.requestVerification() }) { Text("Start verification") }
                }
                VerificationStep.Requesting -> Waiting("Waiting for your other device…", "Accept the request on the other device.") { session.verification.cancel() }
                is VerificationStep.Incoming -> {
                    Text("Verification request", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "${s.deviceName ?: s.deviceId} (${s.userId}) wants to verify with this device.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { session.verification.cancel() }) { Text("Ignore") }
                        Button(onClick = { session.verification.acceptIncoming() }) { Text("Accept") }
                    }
                }
                VerificationStep.Accepted, VerificationStep.Starting -> Waiting("Preparing emoji…", "Both devices are setting up a secure comparison.") { session.verification.cancel() }
                is VerificationStep.Emojis -> {
                    Text("Compare emoji", style = MaterialTheme.typography.headlineSmall)
                    Text("Confirm the emoji below appear in the same order on the other device.", textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
                    s.emojis.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                            row.forEach { e ->
                                Column(
                                    Modifier.size(72.dp, 84.dp).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp)),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center,
                                ) {
                                    Text(e.symbol, fontSize = 32.sp)
                                    Text(e.description, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                }
                            }
                        }
                    }
                    Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { session.verification.decline() }) { Text("They don't match") }
                        Button(onClick = { session.verification.approve() }) { Text("They match") }
                    }
                }
                is VerificationStep.Decimals -> {
                    Text("Compare numbers", style = MaterialTheme.typography.headlineSmall)
                    Text(s.values.joinToString("  "), style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { session.verification.decline() }) { Text("They don't match") }
                        Button(onClick = { session.verification.approve() }) { Text("They match") }
                    }
                }
                VerificationStep.Confirming -> Waiting("Confirming…", "Waiting for the other device to confirm.") { session.verification.cancel() }
                VerificationStep.Done -> {
                    Icon(Icons.Filled.VerifiedUser, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                    Text("Device verified", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(16.dp))
                    Text("Your encrypted messages will now be available here.", textAlign = TextAlign.Center)
                    Button(onClick = { session.verification.reset(); nav.pop() }, modifier = Modifier.padding(top = 16.dp)) { Text("Done") }
                }
                VerificationStep.Cancelled, VerificationStep.Failed -> {
                    Text(if (s == VerificationStep.Failed) "Verification failed" else "Verification cancelled", style = MaterialTheme.typography.headlineSmall)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { session.verification.reset(); nav.pop() }) { Text("Close") }
                        Button(onClick = { session.verification.requestVerification() }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Waiting(title: String, body: String, onCancel: () -> Unit) {
    CircularProgressIndicator(Modifier.padding(24.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(body, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    TextButton(onClick = onCancel) { Text("Cancel") }
}

@Composable
fun NotificationSettingsScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    val status by g.push.status.collectAsState()
    var distributors by remember { mutableStateOf(g.push.distributors()) }
    var current by remember { mutableStateOf(g.push.currentDistributor()) }
    var polling by remember { mutableStateOf(g.settings.pollingEnabled) }
    var groupMode by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var dmMode by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var mentions by remember { mutableStateOf(true) }
    var invites by remember { mutableStateOf(true) }
    var reload by remember { mutableStateOf(0) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(reload) {
        runCatching {
            val s = session.client.getNotificationSettings()
            groupMode = s.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = false)
            dmMode = s.getDefaultRoomNotificationMode(isEncrypted = true, isOneToOne = true)
            mentions = s.isUserMentionEnabled()
            invites = s.isInviteForMeEnabled()
        }
        distributors = g.push.distributors()
        current = g.push.currentDistributor()
    }

    fun setDefault(oneToOne: Boolean, mode: RoomNotificationMode) {
        scope.launch {
            runCatching {
                val s = session.client.getNotificationSettings()
                s.setDefaultRoomNotificationMode(true, oneToOne, mode)
                s.setDefaultRoomNotificationMode(false, oneToOne, mode)
            }.onFailure { context.toast(errorMessage(it)) }
            reload++
        }
    }

    SettingsScaffold("Notifications", nav) {
        Section("Push")
        Text(
            when (val s = status) {
                PushStatus.NotConfigured -> "Push isn't set up. Choose a UnifiedPush distributor below, or enable background polling."
                PushStatus.Registering -> "Registering with your distributor…"
                is PushStatus.Registered -> "Push is active via ${s.distributor}."
                is PushStatus.Failed -> "Push failed: ${s.reason}"
                PushStatus.Polling -> "Background polling is on. Messages may arrive up to ~15 minutes late."
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        (status as? PushStatus.Registered)?.warning?.let { warning ->
            Card(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
        }
        if (current != null) {
            var testing by remember { mutableStateOf(false) }
            var testResult by remember { mutableStateOf<String?>(null) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !testing, onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = g.push.sendTestPush()
                        testing = false
                    }
                }) { Text(if (testing) "Testing…" else "Send test notification") }
                if (testing) CircularProgressIndicator(Modifier.padding(start = 12.dp).size(18.dp), strokeWidth = 2.dp)
            }
            testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        }
        if (distributors.isEmpty()) {
            Card(Modifier.padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    Text("No UnifiedPush distributor found", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Poppy doesn't use Google. For instant, battery-friendly push install a UnifiedPush distributor such as ntfy (works with every Matrix server today), then come back here.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { openUrl(context, "https://unifiedpush.org/users/distributors/") }) { Text("Get a distributor") }
                        TextButton(onClick = { reload++ }) { Text("Refresh") }
                    }
                }
            }
        }
        distributors.forEach { d ->
            ListItem(
                headlineContent = { Text(d.label) },
                supportingContent = { Text(d.packageName) },
                leadingContent = { RadioButton(selected = current == d.packageName, onClick = null) },
                modifier = Modifier.clickable {
                    g.push.select(d.packageName)
                    current = d.packageName
                },
            )
        }
        if (current != null) {
            TextButton(onClick = {
                scope.launch {
                    g.push.disable()
                    current = null
                }
            }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Turn off push") }
        }
        ListItem(
            headlineContent = { Text("Background polling") },
            supportingContent = { Text("Checks for messages every ~15 minutes. Use it when you have no distributor, or as a safety net alongside one. Uses a little more battery.") },
            trailingContent = {
                Switch(polling, onCheckedChange = {
                    polling = it
                    g.push.setPolling(it)
                })
            },
        )

        Section("Defaults")
        ModeRow("Group chats", groupMode) { setDefault(false, it) }
        ModeRow("Direct messages", dmMode) { setDefault(true, it) }
        ListItem(
            headlineContent = { Text("Mentions of me") },
            supportingContent = { Text("Notify when someone mentions you, even in muted-to-mentions rooms") },
            trailingContent = {
                Switch(mentions, {
                    mentions = it
                    scope.launch { runCatching { session.client.getNotificationSettings().setUserMentionEnabled(it) } }
                })
            },
        )
        ListItem(
            headlineContent = { Text("Invitations") },
            trailingContent = {
                Switch(invites, {
                    invites = it
                    scope.launch { runCatching { session.client.getNotificationSettings().setInviteForMeEnabled(it) } }
                })
            },
        )
        ListItem(
            headlineContent = { Text("System notification settings") },
            supportingContent = { Text("Sounds, vibration and channels") },
            modifier = Modifier.clickable {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ModeRow(title: String, mode: RoomNotificationMode?, onChange: (RoomNotificationMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                when (mode) {
                    RoomNotificationMode.ALL_MESSAGES -> "All messages"
                    RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> "Mentions and keywords"
                    RoomNotificationMode.MUTE -> "Off"
                    null -> "…"
                },
            )
        },
        modifier = Modifier.clickable { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    listOf(
                        RoomNotificationMode.ALL_MESSAGES to "All messages",
                        RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY to "Mentions and keywords",
                    ).forEach { (m, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onChange(m); open = false }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(mode == m, null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}

@Composable
fun AppearanceScreen(nav: Navigator) {
    val g = LocalContext.current.graph
    val mode by g.settings.themeMode.collectAsState()
    val dynamic by g.settings.dynamicColor.collectAsState()
    val amoled by g.settings.amoled.collectAsState()
    val typing by g.settings.sendTyping.collectAsState()
    val receipts by g.settings.sendReadReceipts.collectAsState()
    val previews by g.settings.linkPreviews.collectAsState()
    var callUrl by remember { mutableStateOf(g.settings.elementCallUrl) }
    SettingsScaffold("Appearance & privacy", nav) {
        Section("Theme")
        ThemeMode.entries.forEach { m ->
            ListItem(
                headlineContent = { Text(m.name.lowercase().replaceFirstChar { it.uppercase() }.let { if (m == ThemeMode.SYSTEM) "Follow system" else it }) },
                leadingContent = { RadioButton(mode == m, null) },
                modifier = Modifier.clickable { g.settings.setThemeMode(m) },
            )
        }
        if (Build.VERSION.SDK_INT >= 31) {
            ListItem(
                headlineContent = { Text("Material You colours") },
                supportingContent = { Text("Use colours from your wallpaper instead of Poppy red") },
                trailingContent = { Switch(dynamic, g.settings::setDynamicColor) },
            )
        }
        ListItem(
            headlineContent = { Text("Pure black dark theme") },
            supportingContent = { Text("Saves power on OLED screens") },
            trailingContent = { Switch(amoled, g.settings::setAmoled) },
        )
        Section("Privacy")
        ListItem(
            headlineContent = { Text("Send typing notifications") },
            trailingContent = { Switch(typing, g.settings::setSendTyping) },
        )
        ListItem(
            headlineContent = { Text("Link previews") },
            supportingContent = { Text("Show a card for links in messages. Your homeserver fetches the page, so it learns which links you open.") },
            trailingContent = { Switch(previews, g.settings::setLinkPreviews) },
        )
        ListItem(
            headlineContent = { Text("Send read receipts") },
            supportingContent = { Text("When off, receipts are private and only sync between your devices") },
            trailingContent = { Switch(receipts, g.settings::setSendReadReceipts) },
        )
        Section("Calls")
        OutlinedTextField(
            callUrl,
            { callUrl = it; g.settings.elementCallUrl = it.trim() },
            label = { Text("Element Call URL") },
            supportingText = { Text("Calls use Element Call. Change this if your server hosts its own.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    }
}

@Composable
fun AboutScreen(nav: Navigator) {
    val context = LocalContext.current
    SettingsScaffold("About", nav) {
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.ic_poppy), null, Modifier.size(140.dp))
            Text("Poppy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Named after a very good white and black Lakeland Terrier.",
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(16.dp),
            )
        }
        ListItem(
            headlineContent = { Text("Source code") },
            supportingContent = { Text("github.com/sshahs/poppy") },
            leadingContent = { Icon(Icons.Filled.Code, null) },
            modifier = Modifier.clickable { openUrl(context, "https://github.com/sshahs/poppy") },
        )
        ListItem(
            headlineContent = { Text("Share debug logs") },
            supportingContent = { Text("Send the last few days of SDK logs when reporting a problem") },
            leadingContent = { Icon(Icons.Filled.Info, null) },
            modifier = Modifier.clickable { context.shareLogs() },
        )
        ListItem(
            headlineContent = { Text("Matrix Rust SDK") },
            supportingContent = { Text(remember { runCatching { sdkGitSha().take(12) }.getOrDefault("unknown") }) },
        )
    }
}

private fun openUrl(context: android.content.Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}
