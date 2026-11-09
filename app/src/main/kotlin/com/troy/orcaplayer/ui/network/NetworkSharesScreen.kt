package com.troy.orcaplayer.ui.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.troy.orcaplayer.R
import com.troy.orcaplayer.core.design.component.AlertKind
import com.troy.orcaplayer.core.design.component.ButtonKind
import com.troy.orcaplayer.core.design.component.EmptyState
import com.troy.orcaplayer.core.design.component.SectionLabel
import com.troy.orcaplayer.core.design.component.TroyAlert
import com.troy.orcaplayer.core.design.component.TroyButton
import com.troy.orcaplayer.core.design.component.TroyChip
import com.troy.orcaplayer.core.design.component.TroyIconButton
import com.troy.orcaplayer.core.design.component.TroyTextField
import com.troy.orcaplayer.core.design.component.TroyToggle
import com.troy.orcaplayer.core.design.component.troyFocusRing
import com.troy.orcaplayer.core.design.theme.Troy
import com.troy.orcaplayer.sources.network.ShareConnection
import com.troy.orcaplayer.sources.network.ShareType
import com.troy.orcaplayer.ui.common.ConfirmDialog
import com.troy.orcaplayer.ui.shell.BackTopBar

/** Saved network shares and media servers found on the network (20.2). */
@Composable
fun NetworkSharesScreen(onBack: () -> Unit, onOpen: (String) -> Unit, vm: NetworkViewModel = hiltViewModel()) {
    val c = Troy.colors
    val connections by vm.connections.collectAsStateWithLifecycle()
    val servers by vm.servers.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ShareConnection?>(null) }

    editing?.let { conn ->
        ShareEditor(conn, vm, onDone = { editing = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(R.string.network_title), onBack = onBack) {
            TroyIconButton(Icons.Filled.Add, stringResource(R.string.network_add), { editing = blank() })
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (connections.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Filled.Lan,
                        title = stringResource(R.string.network_empty),
                        message = stringResource(R.string.network_empty_message),
                        actionLabel = stringResource(R.string.network_add),
                        onAction = { editing = blank() },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    )
                }
            }
            items(connections, key = { it.id }) { conn ->
                Row(
                    Modifier.fillMaxWidth().troyFocusRing().clickable(role = Role.Button) { onOpen(conn.id) }
                        .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(if (conn.type == ShareType.DLNA) Icons.Filled.Router else Icons.Filled.Dns, null, tint = c.accent, modifier = Modifier.size(28.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conn.name, style = Troy.type.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(describe(conn), style = Troy.type.bodySmall, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TroyIconButton(Icons.Filled.Edit, stringResource(R.string.network_edit), { editing = conn }, tint = c.text2)
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionLabel(stringResource(R.string.network_servers), Modifier.weight(1f))
                    if (discovering) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
                    else TroyButton(stringResource(R.string.network_find), { vm.discover() }, kind = ButtonKind.GHOST)
                }
                Text(stringResource(R.string.network_servers_summary), style = Troy.type.bodySmall, color = c.text3, modifier = Modifier.padding(horizontal = 16.dp))
            }
            val found = servers.orEmpty().filter { s -> connections.none { it.type == ShareType.DLNA && it.host == s.location } }
            if (servers != null && found.isEmpty() && !discovering) {
                item { Text(stringResource(R.string.network_none_found), style = Troy.type.body, color = c.text2, modifier = Modifier.padding(16.dp)) }
            }
            items(found, key = { it.location }) { s ->
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Filled.Router, null, tint = c.text3, modifier = Modifier.size(28.dp))
                    Text(s.name, style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TroyButton(stringResource(R.string.network_add_server), { vm.addServer(s) })
                }
            }
        }
    }
}

private fun blank() = ShareConnection(id = "", type = ShareType.SMB, name = "", host = "")

private fun describe(conn: ShareConnection): String {
    val kind = when (conn.type) {
        ShareType.SMB -> "SMB"
        ShareType.SFTP -> "SFTP"
        ShareType.FTP -> "FTP"
        ShareType.FTPS -> "FTPS"
        ShareType.WEBDAV -> if (conn.secure) "WebDAV (HTTPS)" else "WebDAV"
        ShareType.DLNA -> "DLNA"
    }
    if (conn.type == ShareType.DLNA) return kind
    val where = conn.host + (if (conn.port > 0) ":${conn.port}" else "") + (conn.path.trim('/').takeIf { it.isNotEmpty() }?.let { "/$it" } ?: "")
    return "$kind · $where"
}

/** Adds or changes a connection, with a Test button that lists the start folder without saving. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareEditor(initial: ShareConnection, vm: NetworkViewModel, onDone: () -> Unit) {
    val c = Troy.colors
    val isNew = initial.id.isBlank()
    var type by rememberSaveable { mutableStateOf(initial.type) }
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var host by rememberSaveable { mutableStateOf(initial.host) }
    var port by rememberSaveable { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var path by rememberSaveable { mutableStateOf(initial.path) }
    var user by rememberSaveable { mutableStateOf(initial.username) }
    var pass by rememberSaveable { mutableStateOf(initial.password) }
    var domain by rememberSaveable { mutableStateOf(initial.domain) }
    var secure by rememberSaveable { mutableStateOf(initial.secure) }
    var inLibrary by rememberSaveable { mutableStateOf(initial.inLibrary) }
    var confirmDelete by remember { mutableStateOf(false) }
    val test by vm.test.collectAsStateWithLifecycle()

    fun build() = initial.copy(
        type = type,
        name = name.trim().ifEmpty { host.trim() },
        host = host.trim(),
        port = port.trim().toIntOrNull() ?: 0,
        path = path.trim(),
        username = user.trim(),
        password = pass,
        domain = domain.trim(),
        secure = secure,
        inLibrary = inLibrary,
        hostKey = if (type == initial.type && host.trim() == initial.host && (port.trim().toIntOrNull() ?: 0) == initial.port) initial.hostKey else null,
    )
    fun close() { vm.clearTest(); onDone() }
    BackHandler(onBack = ::close)

    Column(Modifier.fillMaxSize()) {
        BackTopBar(stringResource(if (isNew) R.string.network_add else R.string.network_edit), onBack = ::close) {}
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (type == ShareType.DLNA) {
                Label(R.string.network_name)
                TroyTextField(name, { name = it }, Modifier.fillMaxWidth())
            } else {
                Label(R.string.network_type)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(ShareType.SMB to "SMB", ShareType.SFTP to "SFTP", ShareType.FTP to "FTP", ShareType.FTPS to "FTPS", ShareType.WEBDAV to "WebDAV")
                        .forEach { (t, label) -> TroyChip(label, type == t, { type = t; vm.clearTest() }) }
                }
                Label(R.string.network_host)
                TroyTextField(host, { host = it }, Modifier.fillMaxWidth(), placeholder = "192.168.1.10", keyboardType = KeyboardType.Uri)
                com.troy.orcaplayer.ui.tv.SendFromPhoneButton(
                    stringResource(R.string.network_title),
                    listOf(
                        com.troy.orcaplayer.ui.tv.SendField("host", stringResource(R.string.network_host), value = host),
                        com.troy.orcaplayer.ui.tv.SendField("path", stringResource(if (type == ShareType.SMB) R.string.network_share_name else R.string.network_folder), value = path),
                        com.troy.orcaplayer.ui.tv.SendField("user", stringResource(R.string.network_user), value = user),
                        com.troy.orcaplayer.ui.tv.SendField("pass", stringResource(R.string.network_password), password = true),
                    ),
                ) { got ->
                    got["host"]?.trim()?.let { host = it }
                    got["path"]?.trim()?.let { path = it }
                    got["user"]?.trim()?.let { user = it }
                    got["pass"]?.takeIf { it.isNotEmpty() }?.let { pass = it }
                }
                Label(R.string.network_port)
                TroyTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.network_port_default, defaultPort(type, secure)), keyboardType = KeyboardType.Number)
                Label(if (type == ShareType.SMB) R.string.network_share_name else R.string.network_folder)
                TroyTextField(path, { path = it }, Modifier.fillMaxWidth(), placeholder = if (type == ShareType.SMB) "Media" else "/")
                if (type == ShareType.WEBDAV) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.network_https), style = Troy.type.body, color = c.text, modifier = Modifier.weight(1f))
                        TroyToggle(secure, { secure = it })
                    }
                }
                Label(R.string.network_user)
                TroyTextField(user, { user = it }, Modifier.fillMaxWidth(), placeholder = stringResource(R.string.network_user_hint))
                Label(R.string.network_password)
                TroyTextField(pass, { pass = it }, Modifier.fillMaxWidth(), password = true)
                if (type == ShareType.SMB) {
                    Label(R.string.network_domain)
                    TroyTextField(domain, { domain = it }, Modifier.fillMaxWidth(), placeholder = "WORKGROUP")
                }
                Label(R.string.network_name)
                TroyTextField(name, { name = it }, Modifier.fillMaxWidth(), placeholder = host.ifBlank { stringResource(R.string.network_name_hint) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.network_in_library), style = Troy.type.body, color = c.text)
                        Text(stringResource(R.string.network_in_library_summary), style = Troy.type.bodySmall, color = c.text3)
                    }
                    TroyToggle(inLibrary, { inLibrary = it })
                }
                Text(stringResource(R.string.network_stored_safely), style = Troy.type.bodySmall, color = c.text3)
            }
            when (val t = test) {
                TestResult.Running -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
                    Text(stringResource(R.string.network_testing), style = Troy.type.body, color = c.text2)
                }
                is TestResult.Ok -> TroyAlert(stringResource(R.string.network_test_ok, t.count), AlertKind.OK, Modifier.fillMaxWidth())
                is TestResult.Failed -> TroyAlert(stringResource(R.string.network_test_failed, t.message), AlertKind.DANGER, Modifier.fillMaxWidth())
                null -> Unit
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val valid = host.isNotBlank() && (type != ShareType.SMB || path.isNotBlank())
                TroyButton(stringResource(R.string.network_save), { vm.save(build()); close() }, kind = ButtonKind.PRIMARY, enabled = valid)
                if (type != ShareType.DLNA) TroyButton(stringResource(R.string.network_test), { vm.test(build()) }, enabled = valid && test != TestResult.Running)
                if (!isNew) TroyButton(stringResource(R.string.network_delete), { confirmDelete = true }, kind = ButtonKind.GHOST)
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.network_delete_title),
            message = stringResource(R.string.network_delete_message),
            confirm = stringResource(R.string.network_delete),
            danger = true,
            onConfirm = { confirmDelete = false; vm.delete(initial.id); close() },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun defaultPort(type: ShareType, secure: Boolean): String =
    (if (type == ShareType.WEBDAV && secure) 443 else type.defaultPort).toString()

@Composable
private fun Label(res: Int) {
    Text(stringResource(res), style = Troy.type.label, color = Troy.colors.text2)
}
