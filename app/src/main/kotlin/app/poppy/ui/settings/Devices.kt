package app.poppy.ui.settings

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.ui.Navigator
import app.poppy.ui.components.Time
import app.poppy.util.errorMessage
import app.poppy.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.AccountManagementAction
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Immutable
private data class Device(val id: String, val name: String?, val lastSeenTs: Long, val lastSeenIp: String?)

/** Thin client for the device endpoints the Rust SDK doesn't expose. */
private class DevicesApi(private val session: MatrixSession) {
    private fun base() = session.client.homeserver().trimEnd('/') + "/_matrix/client/v3"

    private fun call(method: String, path: String, body: JSONObject? = null): Pair<Int, JSONObject> {
        val conn = (URL(base() + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Authorization", "Bearer " + session.client.session().accessToken)
            setRequestProperty("Content-Type", "application/json")
            if (body != null) {
                doOutput = true
                outputStream.use { it.write(body.toString().toByteArray()) }
            }
        }
        val code = conn.responseCode
        val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        return code to (runCatching { JSONObject(text) }.getOrDefault(JSONObject()))
    }

    suspend fun list(): List<Device> = withContext(Dispatchers.IO) {
        val (code, json) = call("GET", "/devices")
        if (code != 200) error(json.optString("error", "HTTP $code"))
        val arr = json.optJSONArray("devices") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            Device(d.getString("device_id"), d.optString("display_name").ifBlank { null }, d.optLong("last_seen_ts", 0), d.optString("last_seen_ip").ifBlank { null })
        }.sortedByDescending { it.lastSeenTs }
    }

    suspend fun rename(id: String, name: String) = withContext(Dispatchers.IO) {
        val (code, json) = call("PUT", "/devices/" + enc(id), JSONObject().put("display_name", name))
        if (code != 200) error(json.optString("error", "HTTP $code"))
    }

    /** Deletes a device. Returns null on success, or the UIA session id if a password is needed. */
    suspend fun delete(id: String, password: String? = null, uiaSession: String? = null): String? = withContext(Dispatchers.IO) {
        val body = JSONObject()
        if (password != null) {
            body.put(
                "auth",
                JSONObject()
                    .put("type", "m.login.password")
                    .put("identifier", JSONObject().put("type", "m.id.user").put("user", session.userId))
                    .put("password", password)
                    .apply { uiaSession?.let { put("session", it) } },
            )
        }
        val (code, json) = call("DELETE", "/devices/" + enc(id), body)
        when {
            code == 200 -> null
            code == 401 && json.has("session") && password == null -> json.getString("session")
            else -> error(json.optString("error", "HTTP $code"))
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { DevicesApi(session) }
    val isOAuth = remember { runCatching { session.client.session().oauthData != null }.getOrDefault(false) }
    var devices by remember { mutableStateOf<List<Device>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reload by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Device?>(null) }
    var renaming by remember { mutableStateOf<Device?>(null) }
    var passwordFor by remember { mutableStateOf<Pair<Device, String>?>(null) }

    fun openAccount(action: AccountManagementAction) {
        scope.launch {
            runCatching { session.client.accountUrl(action) }.getOrNull()?.let {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(it))
            } ?: context.toast("Your server doesn't offer an account page")
        }
    }

    LaunchedEffect(reload) {
        loading = true
        runCatching { devices = api.list() }.onFailure { context.toast(errorMessage(it)) }
        loading = false
    }

    fun signOut(d: Device) {
        if (isOAuth) {
            openAccount(AccountManagementAction.DeviceDelete(d.id))
            return
        }
        scope.launch {
            runCatching { api.delete(d.id) }
                .onSuccess { uia -> if (uia != null) passwordFor = d to uia else reload++ }
                .onFailure { context.toast(errorMessage(it)) }
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text("Devices") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    if (isOAuth) IconButton(onClick = { openAccount(AccountManagementAction.DevicesList) }) { Icon(Icons.Filled.OpenInBrowser, "Manage in browser") }
                },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(devices, key = { it.id }) { d ->
                val current = d.id == session.deviceId
                ListItem(
                    leadingContent = { Icon(if (current) Icons.Filled.PhoneAndroid else Icons.Filled.Devices, null) },
                    headlineContent = { Text((d.name ?: d.id) + if (current) " (this device)" else "") },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                d.id,
                                d.lastSeenTs.takeIf { it > 0 }?.let { "last seen ${Time.short(context, it)}" },
                                d.lastSeenIp,
                            ).joinToString(" · "),
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    modifier = Modifier.clickable { selected = d },
                )
            }
        }
    }

    selected?.let { d ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Text(d.name ?: d.id, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            ListItem(headlineContent = { Text("Rename") }, modifier = Modifier.clickable { selected = null; renaming = d })
            if (d.id != session.deviceId) {
                ListItem(
                    headlineContent = { Text("Sign out this device", color = MaterialTheme.colorScheme.error) },
                    modifier = Modifier.clickable { selected = null; signOut(d) },
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.padding(12.dp))
        }
    }

    renaming?.let { d ->
        var name by remember { mutableStateOf(d.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename device") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch { runCatching { api.rename(d.id, name.trim()) }.onSuccess { reload++ }.onFailure { context.toast(errorMessage(it)) } }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    passwordFor?.let { (d, uia) ->
        var password by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { passwordFor = null },
            title = { Text("Confirm it's you") },
            text = {
                Column {
                    Text("Enter your password to sign out ${d.name ?: d.id}.")
                    OutlinedTextField(password, { password = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.padding(top = 12.dp))
                }
            },
            confirmButton = {
                TextButton(enabled = password.isNotEmpty(), onClick = {
                    passwordFor = null
                    scope.launch {
                        runCatching { api.delete(d.id, password, uia) }.onSuccess { reload++ }.onFailure { context.toast(errorMessage(it)) }
                    }
                }) { Text("Sign out") }
            },
            dismissButton = { TextButton(onClick = { passwordFor = null }) { Text("Cancel") } },
        )
    }
}
