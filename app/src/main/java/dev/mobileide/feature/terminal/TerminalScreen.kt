package dev.mobileide.feature.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.mobileide.R
import dev.mobileide.core.designsystem.component.EmptyState
import dev.mobileide.data.terminal.TerminalSession
import dev.mobileide.data.terminal.TerminalSettings
import dev.mobileide.domain.terminal.TerminalEnvironment
import dev.mobileide.domain.terminal.TerminalKey
import dev.mobileide.domain.terminal.TerminalUiState
import dev.mobileide.feature.terminal.components.TerminalInputView
import dev.mobileide.feature.terminal.components.TerminalKeyBar
import dev.mobileide.feature.terminal.components.TerminalOutput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Interactive terminal: session chips, real PTY output, helper keys and a
 * single-line command field. Sessions live in the app-scoped manager, so
 * leaving this screen keeps the shells running.
 */
@Composable
fun TerminalScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val active = uiState.activeSession
    val sessionFlow: Flow<TerminalUiState?> = remember(active) {
        active?.state ?: flowOf<TerminalUiState?>(null)
    }
    val activeUi by sessionFlow.collectAsStateWithLifecycle(initialValue = null)

    var autoScroll by remember { mutableStateOf(true) }
    var showNewMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var isFocused by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        TerminalTopBar(
            title = uiState.projectName.ifBlank { stringResource(R.string.terminal_title) },
            subtitle = activeUi?.info?.let { info ->
                "${environmentShort(info.environment)} · ${info.workingDirectory}"
            }.orEmpty(),
            hasSessions = uiState.sessions.isNotEmpty(),
            onBack = onBack,
            onNew = { showNewMenu = true },
            onMore = { showMoreMenu = true },
            newMenu = {
                DropdownMenu(
                    expanded = showNewMenu,
                    onDismissRequest = { showNewMenu = false }
                ) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.terminal_env_android))
                                Text(
                                    text = stringResource(R.string.terminal_android_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        onClick = {
                            showNewMenu = false
                            viewModel.openSession(TerminalEnvironment.ANDROID)
                        }
                    )
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(R.string.terminal_env_linux))
                                Text(
                                    text = stringResource(R.string.terminal_linux_hint),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        enabled = uiState.linuxAvailable,
                        onClick = {
                            showNewMenu = false
                            viewModel.openSession(TerminalEnvironment.LINUX)
                        }
                    )
                    if (!uiState.linuxAvailable) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.terminal_linux_unavailable),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            enabled = false,
                            onClick = {}
                        )
                    }
                }
            },
            moreMenu = {
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.terminal_clear)) },
                        enabled = active != null,
                        onClick = {
                            showMoreMenu = false
                            viewModel.clearScreen()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.terminal_close)) },
                        enabled = active != null,
                        onClick = {
                            showMoreMenu = false
                            active?.let { viewModel.closeSession(it.id) }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.terminal_close_all)) },
                        enabled = uiState.sessions.isNotEmpty(),
                        onClick = {
                            showMoreMenu = false
                            viewModel.closeAllSessions()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.terminal_font_decrease)) },
                        onClick = {
                            showMoreMenu = false
                            viewModel.setFontSize(uiState.settings.fontSizeSp - 1)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.terminal_font_increase)) },
                        onClick = {
                            showMoreMenu = false
                            viewModel.setFontSize(uiState.settings.fontSizeSp + 1)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.terminal_settings)) },
                        onClick = {
                            showMoreMenu = false
                            viewModel.showSettings(true)
                        }
                    )
                }
            }
        )

        uiState.message?.let { message -> MessageBanner(message, viewModel::dismissMessage) }

        if (uiState.sessions.size > 1) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(uiState.sessions, key = { it.id }) { session ->
                    SessionChip(
                        session = session,
                        selected = session.id == uiState.activeId,
                        onSelect = { viewModel.selectSession(session.id) },
                        onClose = { viewModel.closeSession(session.id) }
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null
                ) {
                    if (activeUi?.info?.isRunning == true) {
                        focusRequester.requestFocus()
                        keyboard?.show()
                    }
                }
        ) {
            val currentUi = activeUi
            when {
                !uiState.ptyAvailable -> TerminalMessage(
                    title = stringResource(R.string.terminal_no_pty),
                    description = stringResource(R.string.terminal_empty_description)
                )

                active == null || currentUi == null -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EmptyState(
                        iconRes = R.drawable.ic_terminal,
                        title = stringResource(R.string.terminal_empty_title),
                        description = stringResource(R.string.terminal_empty_description)
                    )
                    TextButton(onClick = {
                        viewModel.openSession(TerminalEnvironment.ANDROID)
                    }) {
                        Text(stringResource(R.string.terminal_empty_action))
                    }
                }

                else -> TerminalSurface(
                    uiState = currentUi,
                    sessionId = active.id,
                    fontSizeSp = uiState.settings.fontSizeSp,
                    autoScroll = autoScroll,
                    onAutoScrollChange = { autoScroll = it },
                    onResize = viewModel::resize
                )
            }
        }

        activeUi?.info?.let { info ->
            if (!info.isRunning) {
                val message = info.error
                    ?: info.exitCode?.let { stringResource(R.string.terminal_exit_code, it) }
                if (!message.isNullOrBlank()) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                    )
                }
            }
        }

        if (active != null && activeUi?.info?.isRunning == true) {
            TerminalKeyBar(
                onKey = viewModel::sendKey,
                onAltKey = { key ->
                    viewModel.sendBytes(byteArrayOf(0x1B))
                    viewModel.sendKey(key)
                }
            )
            if (!isFocused) {
                Text(
                    text = stringResource(R.string.terminal_tap_to_type),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            AndroidView(
                factory = { context -> TerminalInputView(context) },
                update = { view ->
                    view.onText = { text -> viewModel.sendBytes(text.toByteArray(Charsets.UTF_8)) }
                    view.onBackspace = { count ->
                        val repeat = count.coerceIn(1, 8)
                        viewModel.sendBytes(ByteArray(repeat) { 0x7F })
                    }
                    view.onEnter = { viewModel.sendKey(TerminalKey.ENTER) }
                    view.onArrow = { keyCode ->
                        viewModel.sendKey(
                            when (keyCode) {
                                android.view.KeyEvent.KEYCODE_DPAD_UP -> TerminalKey.ARROW_UP
                                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> TerminalKey.ARROW_DOWN
                                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> TerminalKey.ARROW_LEFT
                                else -> TerminalKey.ARROW_RIGHT
                            }
                        )
                    }
                    view.onControlKey = { code -> viewModel.sendBytes(byteArrayOf(code.toByte())) }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { isFocused = it.isFocused }
            )
        }
    }

    if (uiState.showSettings) {
        TerminalSettingsDialog(
            settings = uiState.settings,
            linuxAvailable = uiState.linuxAvailable,
            onDefaultEnvironment = viewModel::setDefaultEnvironment,
            onFontSize = viewModel::setFontSize,
            onMaxSessions = viewModel::setMaxSessions,
            onDismiss = { viewModel.showSettings(false) }
        )
    }
}

@Composable
private fun TerminalTopBar(
    title: String,
    subtitle: String,
    hasSessions: Boolean,
    onBack: () -> Unit,
    onNew: () -> Unit,
    onMore: () -> Unit,
    newMenu: @Composable () -> Unit,
    moreMenu: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_back),
                contentDescription = stringResource(R.string.action_back),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Box {
            IconButton(onClick = onNew) {
                Icon(
                    painter = painterResource(R.drawable.ic_plus),
                    contentDescription = stringResource(R.string.terminal_new),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            newMenu()
        }
        Box {
            IconButton(onClick = onMore, enabled = hasSessions) {
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.action_more),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            moreMenu()
        }
    }
}

@Composable
private fun TerminalSurface(
    uiState: TerminalUiState,
    sessionId: String,
    fontSizeSp: Int,
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    onResize: (Int, Int) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val measurer = rememberTextMeasurer()
        val style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp
        )
        val charWidth = remember(style) {
            (measurer.measure("MMMMMMMMMM", style).size.width / 10f).coerceAtLeast(1f)
        }
        val lineHeightPx = with(LocalDensity.current) {
            (fontSizeSp * 1.28f).sp.toPx()
        }.coerceAtLeast(1f)
        val horizontalPadding = with(LocalDensity.current) { 8.dp.toPx() }
        val verticalPadding = with(LocalDensity.current) { 8.dp.toPx() }
        val cols = ((constraints.maxWidth - horizontalPadding) / charWidth)
            .toInt().coerceAtLeast(20)
        val rows = ((constraints.maxHeight - verticalPadding) / lineHeightPx)
            .toInt().coerceAtLeast(4)

        LaunchedEffect(cols, rows, sessionId) {
            onResize(cols, rows)
        }

        TerminalOutput(
            snapshot = uiState.snapshot,
            fontSizeSp = fontSizeSp,
            autoScroll = autoScroll,
            onAutoScrollChange = onAutoScrollChange
        )
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_dismiss))
            }
        }
    }
}

@Composable
private fun SessionChip(
    session: TerminalSession,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit
) {
    val state by session.state.collectAsStateWithLifecycle()
    Surface(
        onClick = onSelect,
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        }
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(
                        if (state.info.isRunning) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    )
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "${environmentShort(state.info.environment)} · ${state.info.projectName}",
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = stringResource(R.string.terminal_close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

@Composable
private fun TerminalMessage(title: String, description: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TerminalSettingsDialog(
    settings: TerminalSettings,
    linuxAvailable: Boolean,
    onDefaultEnvironment: (TerminalEnvironment) -> Unit,
    onFontSize: (Int) -> Unit,
    onMaxSessions: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_settings)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.terminal_default_environment),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.defaultEnvironment == TerminalEnvironment.ANDROID,
                        onClick = { onDefaultEnvironment(TerminalEnvironment.ANDROID) },
                        label = { Text(stringResource(R.string.terminal_env_android)) }
                    )
                    FilterChip(
                        selected = settings.defaultEnvironment == TerminalEnvironment.LINUX,
                        onClick = { onDefaultEnvironment(TerminalEnvironment.LINUX) },
                        enabled = linuxAvailable,
                        label = { Text(stringResource(R.string.terminal_env_linux)) }
                    )
                }
                StepperRow(
                    label = stringResource(R.string.terminal_font_size),
                    value = settings.fontSizeSp,
                    onChange = onFontSize
                )
                StepperRow(
                    label = stringResource(R.string.terminal_max_sessions),
                    value = settings.maxSessions,
                    onChange = onMaxSessions
                )
                Text(
                    text = stringResource(R.string.terminal_warning),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp)
                )
                Text(
                    text = stringResource(R.string.terminal_fake_root_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.runtimes_dismiss))
            }
        }
    )
}

@Composable
private fun StepperRow(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = { onChange(value - 1) }) { Text("−") }
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        TextButton(onClick = { onChange(value + 1) }) { Text("+") }
    }
}

private fun environmentShort(environment: TerminalEnvironment): String = when (environment) {
    TerminalEnvironment.ANDROID -> "Android"
    TerminalEnvironment.LINUX -> "Debian"
}
