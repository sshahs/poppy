package app.poppy.ui.login

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.poppy.R
import app.poppy.core.LoginFlow
import app.poppy.graph
import app.poppy.util.copyToClipboard
import app.poppy.util.errorDetails
import app.poppy.util.errorMessage
import app.poppy.util.shareLogs
import app.poppy.util.toast
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val g = context.graph
    val scope = rememberCoroutineScope()
    var server by rememberSaveable { mutableStateOf(g.settings.lastHomeserver) }
    var flow by remember { mutableStateOf<LoginFlow?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf<String?>(null) }
    fun fail(prefix: String?, t: Throwable) {
        error = listOfNotNull(prefix, errorMessage(t)).joinToString(" ")
        val tls = (context.applicationContext as app.poppy.PoppyApp).tlsInitError
        details = errorDetails(t) + (tls?.let { "\nTLS init: $it" } ?: "") +
            "\n\nPoppy ${app.poppy.BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE} (${android.os.Build.SUPPORTED_ABIS.firstOrNull()})"
    }

    fun discover() {
        if (server.isBlank()) return
        busy = true
        error = null
        scope.launch {
            try {
                flow = g.matrix.startLogin(server)
                g.settings.lastHomeserver = server.trim()
            } catch (t: Throwable) {
                fail("Couldn't reach ${server.trim()}.", t)
                val base = details
                details = "$base\n\nRunning connection test…"
                details = "$base\n\n" + runCatching { app.poppy.util.ConnectionDiagnostics.run(context, server) }
                    .getOrElse { "Connection test failed: $it" }
            } finally {
                busy = false
            }
        }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(40.dp))
            Image(painterResource(R.drawable.ic_poppy), contentDescription = null, modifier = Modifier.size(120.dp))
            Text("Poppy", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
            Text(
                "A fast, private Matrix client",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))

            AnimatedContent(targetState = flow, label = "login") { f ->
                Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                    if (f == null) {
                        ServerStep(server, { server = it }, busy, ::discover)
                    } else {
                        MethodStep(
                            flow = f,
                            busy = busy,
                            onBack = {
                                flow = null
                                error = null
                            },
                            onOAuth = {
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        openBrowser(context, g.matrix.oauthUrl(f))
                                    } catch (t: Throwable) {
                                        fail(null, t)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                            onSso = {
                                scope.launch {
                                    try {
                                        openBrowser(context, g.matrix.ssoUrl(f))
                                    } catch (t: Throwable) {
                                        fail(null, t)
                                    }
                                }
                            },
                            onPassword = { user, pass ->
                                busy = true
                                error = null
                                scope.launch {
                                    try {
                                        g.matrix.loginWithPassword(f, user, pass)
                                    } catch (t: Throwable) {
                                        fail(null, t)
                                    } finally {
                                        busy = false
                                    }
                                }
                            },
                        )
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 460.dp),
                )
                details?.let { d ->
                    var expanded by remember(d) { mutableStateOf(false) }
                    Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Hide details" else "Show details") }
                            TextButton(onClick = {
                                context.copyToClipboard(d, "Poppy error")
                                context.toast("Copied")
                            }) { Text("Copy") }
                            TextButton(onClick = { context.shareLogs() }) { Text("Share logs") }
                        }
                        if (expanded) {
                            androidx.compose.foundation.text.selection.SelectionContainer {
                                Text(
                                    d,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ServerStep(server: String, onChange: (String) -> Unit, busy: Boolean, onContinue: () -> Unit) {
    Text("Where is your account?", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = server,
        onValueChange = onChange,
        label = { Text("Homeserver") },
        placeholder = { Text("matrix.org") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { onContinue() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Enter a server name (e.g. matrix.org) or full URL. Poppy will discover the right login method.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(20.dp))
    Button(onClick = onContinue, enabled = !busy && server.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Continue")
    }
}

@Composable
private fun MethodStep(
    flow: LoginFlow,
    busy: Boolean,
    onBack: () -> Unit,
    onOAuth: () -> Unit,
    onSso: () -> Unit,
    onPassword: (String, String) -> Unit,
) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by rememberSaveable { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    val host = remember(flow) { runCatching { Uri.parse(flow.homeserver).host }.getOrNull() ?: flow.homeserver }

    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Change server") }
        Column {
            Text("Sign in to", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(host, style = MaterialTheme.typography.titleMedium)
        }
    }
    Spacer(Modifier.height(16.dp))

    if (flow.supportsOAuth) {
        Button(onClick = onOAuth, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Continue with $host")
        }
        Text(
            "You'll sign in securely in your browser and come straight back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    if (flow.supportsPassword && !flow.supportsOAuth) {
        OutlinedTextField(
            value = user,
            onValueChange = { user = it },
            label = { Text("Username or Matrix ID") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pass,
            onValueChange = { pass = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPass = !showPass }) {
                    Icon(if (showPass) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, "Show password")
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (user.isNotBlank() && pass.isNotEmpty()) onPassword(user, pass) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = { onPassword(user, pass) },
            enabled = !busy && user.isNotBlank() && pass.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Sign in")
        }
    }

    if (flow.supportsSso && !flow.supportsOAuth) {
        if (flow.supportsPassword) {
            Row(Modifier.padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HorizontalDivider(Modifier.weight(1f))
                Text("or", style = MaterialTheme.typography.labelMedium)
                HorizontalDivider(Modifier.weight(1f))
            }
        }
        FilledTonalButton(onClick = onSso, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Continue with single sign-on")
        }
    }

    if (!flow.supportsOAuth && !flow.supportsPassword && !flow.supportsSso) {
        Text("This server doesn't offer a login method Poppy supports.", color = MaterialTheme.colorScheme.error)
    }

    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onBack) { Text("Use a different server") }
}

private fun openBrowser(context: Context, url: String) {
    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, Uri.parse(url))
}
