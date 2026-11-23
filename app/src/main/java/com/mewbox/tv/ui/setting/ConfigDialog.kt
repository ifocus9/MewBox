package com.mewbox.tv.ui.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.ui.components.LocalMewOverlayDismiss
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens

/**
 * 添加 / 编辑订阅底部抽屉。
 * 支持输入网络订阅地址 / JSON 文本，或通过系统 SAF 直接选择本地文件（ZIP / JSON / HTML）。
 * 注意：抽屉只负责收集输入，点「确定」后立即关闭；导入进度与结果由外部 loading / Toast 承担。
 */
@Composable
fun ConfigDialog(
    isEdit: Boolean,
    initialUrl: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String, pickedUri: Uri?) -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val isManagedLocalInitial = FilePickerHelper.isInternalLocalConfigUrl(context, initialUrl)

    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var urlText by rememberSaveable(initialUrl) {
        mutableStateOf(if (isManagedLocalInitial) "" else initialUrl)
    }
    var pickedUriString by rememberSaveable(initialUrl) { mutableStateOf<String?>(null) }
    var pickedFileName by rememberSaveable(initialUrl) { mutableStateOf("") }
    var keepingExistingLocal by rememberSaveable(initialUrl) { mutableStateOf(isManagedLocalInitial) }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = FilePickerHelper.OpenStorageDocument(),
    ) { uri ->
        if (uri != null) {
            val displayName = FilePickerHelper.getFileName(context, uri).ifBlank { "本地文件" }
            pickedUriString = uri.toString()
            pickedFileName = displayName
            keepingExistingLocal = false
            urlText = ""
            if (name.isBlank() || (isManagedLocalInitial && name == initialName)) {
                name = displayName
            }
        }
    }

    val isLocalMode = pickedUriString != null || keepingExistingLocal
    val addressDisplay = when {
        pickedUriString != null -> "本地文件：$pickedFileName"
        keepingExistingLocal -> "本地文件：${initialName.ifBlank { "已导入" }}"
        else -> urlText
    }
    val canConfirm = pickedUriString != null || keepingExistingLocal || urlText.isNotBlank()

    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        val dismiss = LocalMewOverlayDismiss.current ?: onDismiss
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .padding(horizontal = OverlayTokens.Padding)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (isEdit) "编辑订阅" else "添加订阅",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OverlayTokens.DialogTitle,
            )

            Spacer(Modifier.height(14.dp))

            if (isEdit) {
                ConfigInputSlot(
                    label = "订阅名称",
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "输入自定义名称（选填）",
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) },
                    ),
                    trailingContent = {
                        if (name.isNotEmpty()) {
                            IconButton(
                                onClick = { name = "" },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除名称",
                                    tint = OverlayTokens.TextSecondary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
            }

            ConfigInputSlot(
                label = if (isEdit) "订阅地址" else null,
                value = addressDisplay,
                onValueChange = { value ->
                    if (!isLocalMode) {
                        urlText = value
                    }
                },
                readOnly = isLocalMode,
                placeholder = "请输入配置地址或选择本地包",
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        keyboard?.hide()
                        focusManager.clearFocus()
                    },
                ),
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLocalMode || urlText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    pickedUriString = null
                                    pickedFileName = ""
                                    keepingExistingLocal = false
                                    urlText = ""
                                },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除地址",
                                    tint = OverlayTokens.TextSecondary,
                                    modifier = Modifier.size(17.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.size(34.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "选择本地文件",
                                tint = OverlayTokens.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                },
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = dismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OverlayTokens.SheetItemBg,
                        contentColor = OverlayTokens.OutlinedText,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("取消", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }

                Button(
                    onClick = {
                        val pickedUri = pickedUriString?.let { Uri.parse(it) }
                        val confirmUrl = when {
                            pickedUri != null -> ""
                            keepingExistingLocal -> initialUrl
                            else -> urlText.trim()
                        }
                        val confirmName = when {
                            pickedUri != null -> name.trim().ifBlank { pickedFileName }
                            keepingExistingLocal -> name.trim().ifBlank { initialName.ifBlank { "本地订阅" } }
                            else -> name.trim()
                        }
                        onConfirm(confirmUrl, confirmName, pickedUri)
                    },
                    enabled = canConfirm,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f),
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("确定", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ConfigInputSlot(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    label: String? = null,
    readOnly: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailingContent: @Composable () -> Unit = {},
) {
    var isFocused by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        targetValue = if (isFocused && !readOnly) primaryColor.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "configInputBorder",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OverlayTokens.TextPrimary,
            )
            Spacer(Modifier.height(7.dp))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(OverlayTokens.SheetItemBg)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = OverlayTokens.TextSecondary,
                        fontSize = 14.sp,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = OverlayTokens.TextPrimary,
                        fontSize = 14.sp,
                    ),
                    cursorBrush = SolidColor(primaryColor),
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                )
            }
            trailingContent()
        }
    }
}
