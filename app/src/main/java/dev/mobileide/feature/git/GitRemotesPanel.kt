package dev.mobileide.feature.git

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.core.designsystem.theme.MobileIdeDimens
import dev.mobileide.core.designsystem.theme.MobileIdeSpacing
import dev.mobileide.core.util.copyToClipboard
import dev.mobileide.domain.git.GitIdentity
import dev.mobileide.domain.git.GitRemote
import dev.mobileide.domain.git.GitRemoteAuth
import dev.mobileide.domain.git.GitSshKeyInfo
import dev.mobileide.feature.git.model.GitRemotesUiState

/**
 * Remotes tab: remote CRUD, fetch/pull/push, HTTPS credentials, Git identity
 * and SSH key management. Tokens and private keys stay in local dialog state
 * until they are handed to the repository; they are never put in UI state
 * flows or saved instance state.
 */
@Composable
internal fun GitRemotesPanel(
    state: GitRemotesUiState,
    busy: Boolean,
    onRefresh: () -> Unit,
    onAddRemote: (String, String) -> Unit,
    onSetRemoteUrl: (String, String) -> Unit,
    onRemoveRemote: (String) -> Unit,
    onFetch: (String) -> Unit,
    onPull: (String) -> Unit,
    onPush: (String) -> Unit,
    onSaveIdentity: (String, String) -> Unit,
    onSaveCredential: (String, String, String) -> Unit,
    onClearCredential: (String) -> Unit,
    onGenerateSshKey: () -> Unit,
    onImportSshKey: (String, String?, String?) -> Unit,
    onDeleteSshKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var addName by rememberSaveable { mutableStateOf("") }
    // URLs may contain an embedded token; never put them in saved state.
    var addUrl by remember { mutableStateOf("") }
    var editTarget by remember { mutableStateOf<GitRemote?>(null) }
    var editUrl by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<GitRemote?>(null) }

    var credentialTarget by remember { mutableStateOf<GitRemote?>(null) }
    var credentialUsername by remember { mutableStateOf("") }
    var credentialToken by remember { mutableStateOf("") }

    var showIdentityDialog by rememberSaveable { mutableStateOf(false) }
    var identityName by rememberSaveable { mutableStateOf("") }
    var identityEmail by rememberSaveable { mutableStateOf("") }

    var showSshDialog by rememberSaveable { mutableStateOf(false) }
    var showGenerateConfirm by rememberSaveable { mutableStateOf(false) }
    var showImportDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteSshConfirm by rememberSaveable { mutableStateOf(false) }
    var importPrivateKey by remember { mutableStateOf("") }
    var importPublicKey by remember { mutableStateOf("") }
    var importPassphrase by remember { mutableStateOf("") }

    fun openCredential(remote: GitRemote) {
        credentialTarget = remote
        credentialUsername = remote.credentialUsername.orEmpty()
        credentialToken = ""
    }

    fun openIdentity() {
        identityName = state.identity.name.orEmpty()
        identityEmail = state.identity.email.orEmpty()
        showIdentityDialog = true
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .widthIn(max = MobileIdeDimens.contentMaxWidth)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = MobileIdeDimens.screenHorizontalPadding,
                end = MobileIdeDimens.screenHorizontalPadding,
                top = MobileIdeSpacing.sm,
                bottom = MobileIdeSpacing.xxxl
            ),
            verticalArrangement = Arrangement.spacedBy(MobileIdeSpacing.sm)
        ) {
            item(key = "header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.git_remotes_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { showAddDialog = true }, enabled = !busy) {
                        Text(stringResource(R.string.git_remote_add))
                    }
                }
            }

            if (state.isLoading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MobileIdeSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }

            state.error?.let { error ->
                item(key = "error") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(MobileIdeSpacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = error.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                error.detail?.let { detail ->
                                    Text(
                                        text = detail,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(onClick = onRefresh) {
                                Text(
                                    text = stringResource(R.string.git_retry),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }
            }

            if (!state.isLoading && state.error == null && state.remotes.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        iconRes = R.drawable.ic_git,
                        title = stringResource(R.string.git_remote_empty_title),
                        description = stringResource(R.string.git_remote_empty_description)
                    )
                }
            }

            items(items = state.remotes, key = { it.name }) { remote ->
                RemoteCard(
                    remote = remote,
                    busy = busy,
                    onFetch = { onFetch(remote.name) },
                    onPull = { onPull(remote.name) },
                    onPush = { onPush(remote.name) },
                    onEdit = {
                        editTarget = remote
                        editUrl = remote.url
                    },
                    onDelete = { deleteTarget = remote },
                    onCredential = { openCredential(remote) },
                    onSshKey = { showSshDialog = true }
                )
            }

            item(key = "identity") {
                IdentityCard(
                    identity = state.identity,
                    onEdit = { openIdentity() }
                )
            }

            item(key = "ssh") {
                SshKeyCard(
                    sshKey = state.sshKey,
                    onManage = { showSshDialog = true }
                )
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.git_remote_add_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = addName,
                        onValueChange = { addName = it },
                        label = { Text(stringResource(R.string.git_remote_name_label)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = addUrl,
                        onValueChange = { addUrl = it },
                        label = { Text(stringResource(R.string.git_remote_url_label)) },
                        supportingText = {
                            Text(stringResource(R.string.git_remote_url_hint))
                        },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onAddRemote(addName.trim(), addUrl.trim())
                        showAddDialog = false
                        addName = ""
                        addUrl = ""
                    },
                    enabled = addName.isNotBlank() && addUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_remote_add_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    editTarget?.let { remote ->
        AlertDialog(
            onDismissRequest = { editTarget = null },
            title = { Text(stringResource(R.string.git_remote_edit_title)) },
            text = {
                OutlinedTextField(
                    value = editUrl,
                    onValueChange = { editUrl = it },
                    label = { Text(stringResource(R.string.git_remote_url_label)) },
                    supportingText = { Text(stringResource(R.string.git_remote_url_hint)) },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSetRemoteUrl(remote.name, editUrl.trim())
                        editTarget = null
                    },
                    enabled = editUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_remote_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editTarget = null }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    deleteTarget?.let { remote ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.git_remote_delete_title)) },
            text = { Text(stringResource(R.string.git_remote_delete_message, remote.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveRemote(remote.name)
                        deleteTarget = null
                    }
                ) {
                    Text(
                        text = stringResource(R.string.git_remote_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    credentialTarget?.let { remote ->
        AlertDialog(
            onDismissRequest = {
                credentialTarget = null
                credentialToken = ""
            },
            title = { Text(stringResource(R.string.git_credential_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.git_credential_host, remote.host.orEmpty()))
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = credentialUsername,
                        onValueChange = { credentialUsername = it },
                        label = { Text(stringResource(R.string.git_credential_username)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = credentialToken,
                        onValueChange = { credentialToken = it },
                        label = { Text(stringResource(R.string.git_credential_token)) },
                        supportingText = {
                            if (remote.hasCredential) {
                                Text(stringResource(R.string.git_credential_token_hint))
                            }
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(R.string.git_credential_security),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (remote.hasCredential) {
                        TextButton(
                            onClick = {
                                onClearCredential(remote.host.orEmpty())
                                credentialTarget = null
                                credentialToken = ""
                            }
                        ) {
                            Text(
                                text = stringResource(R.string.git_credential_clear),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveCredential(
                            remote.host.orEmpty(),
                            credentialUsername.trim(),
                            credentialToken.trim()
                        )
                        credentialTarget = null
                        credentialToken = ""
                    },
                    enabled = credentialUsername.isNotBlank() && credentialToken.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_credential_save))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        credentialTarget = null
                        credentialToken = ""
                    }
                ) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    if (showIdentityDialog) {
        AlertDialog(
            onDismissRequest = { showIdentityDialog = false },
            title = { Text(stringResource(R.string.git_identity_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = identityName,
                        onValueChange = { identityName = it },
                        label = { Text(stringResource(R.string.git_identity_name)) },
                        singleLine = true
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = identityEmail,
                        onValueChange = { identityEmail = it },
                        label = { Text(stringResource(R.string.git_identity_email)) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onSaveIdentity(identityName.trim(), identityEmail.trim())
                        showIdentityDialog = false
                    },
                    enabled = identityName.isNotBlank() && identityEmail.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_identity_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showIdentityDialog = false }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    if (showSshDialog) {
        AlertDialog(
            onDismissRequest = { showSshDialog = false },
            title = { Text(stringResource(R.string.git_ssh_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = stringResource(
                            if (state.sshKey.hasKey) {
                                R.string.git_ssh_status_ready
                            } else {
                                R.string.git_ssh_status_missing
                            }
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (state.sshKey.hasKey) {
                        Spacer(Modifier.height(MobileIdeSpacing.xs))
                        Text(
                            text = stringResource(
                                if (state.sshKey.hasPassphrase) {
                                    R.string.git_ssh_passphrase_protected
                                } else {
                                    R.string.git_ssh_no_passphrase
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.sshKey.publicKey?.let { publicKey ->
                            Spacer(Modifier.height(MobileIdeSpacing.sm))
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = MaterialTheme.colorScheme.surfaceContainer
                            ) {
                                SelectionContainer {
                                    Text(
                                        text = publicKey,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace
                                        ),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(MobileIdeSpacing.sm),
                                        maxLines = 6,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    context.copyToClipboard("SSH public key", publicKey)
                                }
                            ) {
                                Text(stringResource(R.string.git_ssh_copy_public))
                            }
                        }
                    }
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(R.string.git_ssh_private_path, state.sshKey.privateKeyPath),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Text(
                        text = stringResource(R.string.git_ssh_security_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(MobileIdeSpacing.xs)) {
                        TextButton(
                            onClick = {
                                showSshDialog = false
                                showGenerateConfirm = true
                            }
                        ) {
                            Text(stringResource(R.string.git_ssh_generate))
                        }
                        TextButton(
                            onClick = {
                                showSshDialog = false
                                showImportDialog = true
                            }
                        ) {
                            Text(stringResource(R.string.git_ssh_import))
                        }
                        if (state.sshKey.hasKey) {
                            TextButton(
                                onClick = {
                                    showSshDialog = false
                                    showDeleteSshConfirm = true
                                }
                            ) {
                                Text(
                                    text = stringResource(R.string.git_ssh_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSshDialog = false }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    if (showGenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showGenerateConfirm = false },
            title = { Text(stringResource(R.string.git_ssh_generate_title)) },
            text = { Text(stringResource(R.string.git_ssh_generate_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onGenerateSshKey()
                        showGenerateConfirm = false
                        showSshDialog = false
                    }
                ) {
                    Text(stringResource(R.string.git_ssh_generate))
                }
            },
            dismissButton = {
                TextButton(onClick = { showGenerateConfirm = false }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = {
                showImportDialog = false
                importPrivateKey = ""
                importPublicKey = ""
                importPassphrase = ""
            },
            title = { Text(stringResource(R.string.git_ssh_import_title)) },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = importPrivateKey,
                        onValueChange = { importPrivateKey = it },
                        label = { Text(stringResource(R.string.git_ssh_private_label)) },
                        minLines = 4,
                        maxLines = 8
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = importPublicKey,
                        onValueChange = { importPublicKey = it },
                        label = { Text(stringResource(R.string.git_ssh_public_label)) },
                        minLines = 2,
                        maxLines = 4
                    )
                    Spacer(Modifier.height(MobileIdeSpacing.sm))
                    OutlinedTextField(
                        value = importPassphrase,
                        onValueChange = { importPassphrase = it },
                        label = { Text(stringResource(R.string.git_ssh_passphrase_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onImportSshKey(
                            importPrivateKey,
                            importPublicKey.trim().takeIf { it.isNotEmpty() },
                            importPassphrase.takeIf { it.isNotEmpty() }
                        )
                        showImportDialog = false
                        showSshDialog = false
                        importPrivateKey = ""
                        importPublicKey = ""
                        importPassphrase = ""
                    },
                    enabled = importPrivateKey.isNotBlank()
                ) {
                    Text(stringResource(R.string.git_ssh_import))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showImportDialog = false
                        importPrivateKey = ""
                        importPublicKey = ""
                        importPassphrase = ""
                    }
                ) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }

    if (showDeleteSshConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteSshConfirm = false },
            title = { Text(stringResource(R.string.git_ssh_delete_title)) },
            text = { Text(stringResource(R.string.git_ssh_delete_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteSshKey()
                        showDeleteSshConfirm = false
                        showSshDialog = false
                    }
                ) {
                    Text(
                        text = stringResource(R.string.git_ssh_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteSshConfirm = false }) {
                    Text(stringResource(R.string.git_cancel))
                }
            }
        )
    }
}

@Composable
private fun RemoteCard(
    remote: GitRemote,
    busy: Boolean,
    onFetch: () -> Unit,
    onPull: () -> Unit,
    onPush: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCredential: () -> Unit,
    onSshKey: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = remote.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AuthBadge(remote.auth)
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.action_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.git_remote_edit)) },
                            onClick = {
                                menuOpen = false
                                onEdit()
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.git_remote_delete),
                                    color = MaterialTheme.colorScheme.error
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(MobileIdeSpacing.xs))
            SelectionContainer {
                Text(
                    text = remote.url,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when (remote.auth) {
                GitRemoteAuth.HTTPS -> {
                    Spacer(Modifier.height(MobileIdeSpacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (remote.hasCredential) {
                                remote.credentialUsername?.let { username ->
                                    stringResource(
                                        R.string.git_remote_credential_saved_user,
                                        username
                                    )
                                } ?: stringResource(R.string.git_remote_credential_saved)
                            } else {
                                stringResource(R.string.git_remote_credential_missing)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remote.hasCredential) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onCredential) {
                            Text(stringResource(R.string.git_remote_credential))
                        }
                    }
                }

                GitRemoteAuth.SSH -> {
                    Spacer(Modifier.height(MobileIdeSpacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(
                                if (remote.hasCredential) {
                                    R.string.git_remote_ssh_ready
                                } else {
                                    R.string.git_remote_ssh_missing
                                }
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (remote.hasCredential) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onSshKey) {
                            Text(stringResource(R.string.git_remote_ssh_key))
                        }
                    }
                }

                GitRemoteAuth.OTHER -> Unit
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onFetch, enabled = !busy) {
                    Text(stringResource(R.string.git_remote_fetch))
                }
                TextButton(onClick = onPull, enabled = !busy) {
                    Text(stringResource(R.string.git_remote_pull))
                }
                TextButton(onClick = onPush, enabled = !busy) {
                    Text(stringResource(R.string.git_remote_push))
                }
            }
        }
    }
}

@Composable
private fun AuthBadge(auth: GitRemoteAuth) {
    val label = when (auth) {
        GitRemoteAuth.HTTPS -> stringResource(R.string.git_remote_https)
        GitRemoteAuth.SSH -> stringResource(R.string.git_remote_ssh)
        GitRemoteAuth.OTHER -> stringResource(R.string.git_remote_other)
    }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun IdentityCard(identity: GitIdentity, onEdit: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.git_identity_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onEdit) {
                    Text(stringResource(R.string.git_identity_edit))
                }
            }
            Text(
                text = if (identity.name.isNullOrBlank() || identity.email.isNullOrBlank()) {
                    stringResource(R.string.git_identity_not_set)
                } else {
                    "${identity.name} <${identity.email}>"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SshKeyCard(sshKey: GitSshKeyInfo, onManage: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(MobileIdeSpacing.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.git_ssh_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onManage) {
                    Text(stringResource(R.string.git_ssh_manage))
                }
            }
            Text(
                text = stringResource(
                    if (sshKey.hasKey) {
                        R.string.git_ssh_status_ready
                    } else {
                        R.string.git_ssh_status_missing
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (sshKey.hasKey) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            sshKey.publicKey?.let { publicKey ->
                Spacer(Modifier.height(MobileIdeSpacing.xs))
                Text(
                    text = publicKey,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
