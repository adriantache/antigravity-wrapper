package com.adriantache.antigravitywrapper.ui.components

import android.view.InputDevice
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.adriantache.antigravitywrapper.bridge.ComposerState
import com.adriantache.antigravitywrapper.bridge.ModelOption
import com.adriantache.antigravitywrapper.bridge.PermissionOption

private val ComposerBackdrop = Color(0xFF101010)
private val ComposerCardBg = Color(0xFF1A1A1D)
private val ComposerBorder = Color(0xFF2D2D32)
private val ComposerChipBg = Color(0xFF242429)
private val ComposerMutedText = Color(0xFF9A9AA2)

internal enum class EnterKeyAction {
    SEND,
    CONSUME_ONLY,
    PASS_THROUGH
}

internal fun resolveExternalKeyboardEnterAction(
    isEnterKey: Boolean,
    isHardwareKeyboard: Boolean,
    isShiftPressed: Boolean,
    isKeyDown: Boolean,
    repeatCount: Int,
    canSend: Boolean
): EnterKeyAction {
    if (!isEnterKey || !isHardwareKeyboard || isShiftPressed) {
        return EnterKeyAction.PASS_THROUGH
    }
    return if (isKeyDown && repeatCount == 0 && canSend) {
        EnterKeyAction.SEND
    } else {
        EnterKeyAction.CONSUME_ONLY
    }
}

private fun isHardwareKeyboardEvent(nativeEvent: android.view.KeyEvent): Boolean {
    if (nativeEvent.deviceId <= 0) return false
    val device = InputDevice.getDevice(nativeEvent.deviceId) ?: return false
    return !device.isVirtual &&
        (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
}

@Composable
fun NativeComposerBar(
    modifier: Modifier = Modifier,
    text: String,
    onTextChange: (String) -> Unit,
    composerState: ComposerState,
    attachedFileNames: List<String> = emptyList(),
    isAttaching: Boolean = false,
    isSending: Boolean,
    showVoiceButton: Boolean,
    isFloatingOverlay: Boolean = false,
    focusRequester: FocusRequester? = null,
    onSend: (fromHardwareKeyboard: Boolean) -> Unit,
    onStop: () -> Unit,
    onOpenModelPicker: () -> Unit,
    onOpenPermissionsPicker: () -> Unit,
    onAttachClick: () -> Unit,
    onClearAttachments: () -> Unit = {},
    onVoiceClick: () -> Unit,
    onSwitchToWebComposer: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isFloatingOverlay) Color.Transparent else ComposerBackdrop,
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            // Concentric corner math with uniform 8.dp card padding:
            // Top: 32.dp pill controls (16.dp radius) + 8.dp padding = 24.dp
            // Bottom: 44.dp circular buttons (22.dp radius) + 8.dp padding = 30.dp
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(
                    topStart = 24.dp,
                    topEnd = 24.dp,
                    bottomStart = 30.dp,
                    bottomEnd = 30.dp
                ),
                color = ComposerCardBg,
                border = BorderStroke(1.dp, ComposerBorder),
                shadowElevation = if (isFloatingOverlay) 8.dp else 0.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Top bar: Model selector chip, Permissions chip, and Switch-to-Web button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (composerState.currentModel.isNotBlank()) {
                                ComposerSelectorChip(
                                    icon = Icons.Default.AutoAwesome,
                                    label = composerState.currentModel,
                                    onClick = onOpenModelPicker
                                )
                            }

                            if (composerState.currentPermissions.isNotBlank()) {
                                ComposerSelectorChip(
                                    icon = Icons.Default.Shield,
                                    label = composerState.currentPermissions,
                                    onClick = onOpenPermissionsPicker
                                )
                            }
                        }

                        // Quick fallback to the web composer (e.g. for @ mentions or / slash commands)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .clickable(onClick = onSwitchToWebComposer),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = "Switch to web input",
                                tint = ComposerMutedText,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Attached files preview row
                    if (attachedFileNames.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            attachedFileNames.forEach { fileName ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = ComposerChipBg
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AttachFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text(
                                            text = fileName,
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                            Surface(
                                shape = CircleShape,
                                color = ComposerChipBg,
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                                    .clickable(onClick = onClearAttachments)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Clear attachments",
                                        tint = ComposerMutedText,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }

                    val hasContent = text.isNotBlank() || attachedFileNames.isNotEmpty()
                    val showStop = composerState.isGenerating && !hasContent && !isSending
                    val canSend = hasContent && !isSending

                    // Bottom bar: Attach + Native TextField + Optional Voice + Large Send/Stop
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (composerState.hasAttach) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(ComposerChipBg)
                                    .clickable(enabled = !isAttaching, onClick = onAttachClick),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isAttaching) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = Color.White
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = "Attach file",
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }

                        // Native Compose TextField for buttery 120Hz typing
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 44.dp, max = 148.dp)
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (text.isEmpty()) {
                                Text(
                                    text = "Ask anything…",
                                    style = TextStyle(
                                        color = ComposerMutedText,
                                        fontSize = 15.sp,
                                        lineHeight = 21.sp
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            BasicTextField(
                                value = text,
                                onValueChange = onTextChange,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .then(
                                        if (focusRequester != null) {
                                            Modifier.focusRequester(focusRequester)
                                        } else {
                                            Modifier
                                        }
                                    )
                                    .onPreviewKeyEvent { keyEvent ->
                                        val isEnter = keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter
                                        val action = resolveExternalKeyboardEnterAction(
                                            isEnterKey = isEnter,
                                            isHardwareKeyboard = isHardwareKeyboardEvent(keyEvent.nativeKeyEvent),
                                            isShiftPressed = keyEvent.isShiftPressed,
                                            isKeyDown = keyEvent.type == KeyEventType.KeyDown,
                                            repeatCount = keyEvent.nativeKeyEvent.repeatCount,
                                            canSend = canSend
                                        )
                                        when (action) {
                                            EnterKeyAction.SEND -> {
                                                onSend(true)
                                                true
                                            }
                                            EnterKeyAction.CONSUME_ONLY -> true
                                            EnterKeyAction.PASS_THROUGH -> false
                                        }
                                    },
                                textStyle = TextStyle(
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.Sentences
                                ),
                                maxLines = 6
                            )
                        }

                        // Optional Voice button (off by default, clearly spaced away from Send)
                        if (showVoiceButton && composerState.hasVoiceButton) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(ComposerChipBg)
                                    .clickable(onClick = onVoiceClick),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Mic,
                                    contentDescription = "Record voice memo",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(2.dp))
                        }

                        // Large 44dp Send / Stop button (matched to Attach button for concentric symmetry)
                        val buttonColor = when {
                            showStop -> Color(0xFFD94A4A)
                            canSend -> MaterialTheme.colorScheme.primary
                            else -> ComposerChipBg
                        }
                        val iconTint = when {
                            showStop -> Color.White
                            canSend -> MaterialTheme.colorScheme.onPrimary
                            else -> ComposerMutedText
                        }

                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(buttonColor)
                                .clickable(enabled = showStop || canSend) {
                                    if (showStop) onStop() else onSend(false)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            when {
                                isSending -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                showStop -> Icon(
                                    imageVector = Icons.Default.Stop,
                                    contentDescription = "Stop generating",
                                    tint = iconTint,
                                    modifier = Modifier.size(22.dp)
                                )
                                else -> Icon(
                                    imageVector = Icons.Default.ArrowUpward,
                                    contentDescription = "Send message",
                                    tint = iconTint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposerSelectorChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = CircleShape,
        color = ComposerChipBg,
        modifier = Modifier
            .height(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = ComposerMutedText,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelPickerBottomSheet(
    models: List<ModelOption>,
    isLoading: Boolean,
    subOptionsTitle: String? = null,
    subOptions: List<ModelOption> = emptyList(),
    onBackFromSubOptions: () -> Unit = {},
    onSelect: (ModelOption) -> Unit,
    onSelectSubOption: (ModelOption) -> Unit = {},
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val showingSubOptions = !subOptionsTitle.isNullOrBlank() && subOptions.isNotEmpty()
    val displayedItems = if (showingSubOptions) subOptions else models

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ComposerCardBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showingSubOptions) {
                    IconButton(
                        onClick = onBackFromSubOptions,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to models",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                } else {
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (showingSubOptions) subOptionsTitle.orEmpty() else "Select Model",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
            HorizontalDivider(color = ComposerBorder)

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                displayedItems.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No models available right now.",
                            color = ComposerMutedText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 460.dp)
                    ) {
                        items(displayedItems) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .alpha(if (option.isDisabled) 0.45f else 1f)
                                    .clickable(enabled = !option.isDisabled) {
                                        if (showingSubOptions) onSelectSubOption(option) else onSelect(option)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    val showEffortInline = option.effort.isNotBlank() && option.effort.length <= 12
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = option.name,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = if (option.isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (showEffortInline) {
                                            Text(
                                                text = option.effort,
                                                color = ComposerMutedText,
                                                fontSize = 13.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    if (option.effort.isNotBlank() && !showEffortInline) {
                                        Text(
                                            text = option.effort,
                                            color = ComposerMutedText,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (option.badge.isNotBlank()) {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = ComposerChipBg
                                    ) {
                                        Text(
                                            text = option.badge,
                                            color = if (option.badge.equals("New", ignoreCase = true)) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                ComposerMutedText
                                            },
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }

                                if (option.isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsPickerBottomSheet(
    options: List<PermissionOption>,
    isLoading: Boolean,
    onSelect: (PermissionOption) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ComposerCardBg,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = "Agent Permissions",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            HorizontalDivider(color = ComposerBorder)

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(28.dp))
                    }
                }
                options.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No permission presets found.",
                            color = ComposerMutedText,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp)
                    ) {
                        items(options) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option) }
                                    .padding(horizontal = 20.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = option.label,
                                        color = Color.White,
                                        fontSize = 15.sp,
                                        fontWeight = if (option.isSelected) FontWeight.SemiBold else FontWeight.Normal
                                    )
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            text = option.description,
                                            color = ComposerMutedText,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                                if (option.isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
