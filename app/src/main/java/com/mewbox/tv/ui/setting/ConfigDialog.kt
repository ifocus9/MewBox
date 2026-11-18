package com.mewbox.tv.ui.setting

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.ui.components.MewDialogShell
import com.mewbox.tv.ui.components.MewDialogSpec
import com.mewbox.tv.ui.components.OverlayTokens

private val BluePrimary = OverlayTokens.Accent
private val DialogOutlinedBg = OverlayTokens.ItemBg
private val DialogOutlinedText = OverlayTokens.OutlinedText

/**
 * 添加 / 编辑订阅弹窗。
 * 支持输入网络订阅地址 / JSON 文本，或通过系统 SAF 直接选择本地文件（ZIP / JSON / HTML）。
 * 注意：弹窗只负责收集输入，点「确定」后立即关闭；导入进度与结果由外部 loading / Toast 承担。
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

    MewDialogShell(
        onDismiss = onDismiss,
        spec = MewDialogSpec.Compact,
    ) {
        Column(
            modifier = Modifier.padding(OverlayTokens.Padding),
        ) {
            Text(
                text = if (isEdit) "编辑订阅" else "添加订阅",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF202124),
            )

            Spacer(Modifier.height(14.dp))

            if (isEdit) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("订阅名称", color = Color(0xFF8A8F98), fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BluePrimary,
                        unfocusedBorderColor = Color(0xFFDADCE0),
                        focusedContainerColor = Color.White,
                        unfocusedContainerColor = Color.White,
                        cursorColor = BluePrimary,
                    ),
                )
                Spacer(Modifier.height(10.dp))
            }

            // 地址输入框独占整行：清除 / 本地文件收成紧凑尾图标，避免长链接被右侧方块挤成多行
            OutlinedTextField(
                value = addressDisplay,
                onValueChange = { value ->
                    if (!isLocalMode) {
                        urlText = value
                    }
                },
                readOnly = isLocalMode,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("请输入配置地址或选择本地包", color = Color(0xFF8A8F98), fontSize = 14.sp) },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                trailingIcon = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(end = 4.dp),
                    ) {
                        if (isLocalMode || urlText.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    pickedUriString = null
                                    pickedFileName = ""
                                    keepingExistingLocal = false
                                    urlText = ""
                                },
                                modifier = Modifier.size(30.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除地址",
                                    tint = Color(0xFF8E8E93),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                        IconButton(
                            onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = "选择本地文件",
                                tint = Color(0xFF8E8E93),
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BluePrimary,
                    unfocusedBorderColor = Color(0xFFDADCE0),
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    cursorColor = BluePrimary,
                ),
            )

            Spacer(Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DialogOutlinedBg,
                        contentColor = DialogOutlinedText,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("取消", fontSize = 15.sp)
                }

                Spacer(Modifier.width(10.dp))

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
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BluePrimary,
                        contentColor = Color.White,
                        disabledContainerColor = BluePrimary.copy(alpha = 0.4f),
                        disabledContentColor = Color.White.copy(alpha = 0.7f),
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("确定", fontSize = 15.sp)
                }
            }
        }
    }
}
