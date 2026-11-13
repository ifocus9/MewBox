package com.mewbox.tv.ui.setting

import android.Manifest
import com.mewbox.tv.util.AppToast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val BluePrimary = Color(0xFF217AF4)
private val DialogOutlinedBg = Color(0xFFF2F4F7)
private val DialogOutlinedText = Color(0xFF4E5564)

/**
 * 添加 / 编辑订阅弹窗
 * 支持输入网络订阅地址、JSON 文本或选择本地文件。
 * 注意：弹窗只负责收集输入，点「确定」后立即关闭；导入进度与结果由外部 loading / Toast 承担。
 */
@Composable
fun ConfigDialog(
    isEdit: Boolean,
    initialUrl: String,
    initialName: String,
    onDismiss: () -> Unit,
    onConfirm: (url: String, name: String) -> Unit,
) {
    val context = LocalContext.current
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }

    // 本地文件选择器
    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val localPath = FilePickerHelper.resolveLocalFilePath(context, uri)
            if (localPath.isNotBlank()) {
                url = localPath
                val fileName = FilePickerHelper.getFileName(context, uri)
                if (fileName.isNotBlank() && name.isBlank()) {
                    name = fileName.substringBeforeLast(".")
                }
            } else {
                AppToast.show("获取本地文件路径失败")
            }
        }
    }

    // Android 11+ 管理所有文件权限回调
    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        if (FilePickerHelper.hasStoragePermission(context)) {
            openDocLauncher.launch(arrayOf("*/*"))
        } else {
            AppToast.show("未授予文件访问权限")
        }
    }

    // Android 10 及以下运行时权限回调
    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        val granted = results[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
            FilePickerHelper.hasStoragePermission(context)
        if (granted) {
            openDocLauncher.launch(arrayOf("*/*"))
        } else {
            AppToast.show("未授予文件访问权限")
        }
    }

    // 点击文件夹按钮：先检查权限并申请，已获授权则直接调起系统文件选择器
    val onPickFileClick: () -> Unit = {
        if (FilePickerHelper.hasStoragePermission(context)) {
            openDocLauncher.launch(arrayOf("*/*"))
        } else {
            AppToast.show("请先授予文件访问权限")
            FilePickerHelper.requestStoragePermission(context, manageStorageLauncher, requestPermissionsLauncher)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 440.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // 标题（编辑订阅 / 添加订阅）
                Text(
                    text = if (isEdit) "编辑订阅" else "添加订阅",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124),
                )

                Spacer(Modifier.height(14.dp))

                // 长按编辑模式下展示名称输入框
                if (isEdit) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("订阅名称（留空则用链接）", color = Color(0xFF8A8F98), fontSize = 13.sp) },
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp),
                        trailingIcon = {
                            if (name.isNotEmpty()) {
                                IconButton(onClick = { name = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清除名称",
                                        tint = Color(0xFF8E8E93),
                                        modifier = Modifier.size(18.dp),
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
                    Spacer(Modifier.height(10.dp))
                }

                // 地址输入框独占整行：清除 / 本地文件收成紧凑尾图标，避免长链接被右侧方块挤成多行
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("请输入配置地址", color = Color(0xFF8A8F98), fontSize = 14.sp) },
                    maxLines = 3,
                    shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (url.isNotEmpty()) {
                                IconButton(
                                    onClick = { url = "" },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清除地址",
                                        tint = Color(0xFF8E8E93),
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                            IconButton(
                                onClick = onPickFileClick,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOpen,
                                    contentDescription = "选择本地文件",
                                    tint = Color(0xFF8E8E93),
                                    modifier = Modifier.size(24.dp),
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

                // 底部操作按钮
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
                        onClick = { onConfirm(url.trim(), name.trim()) },
                        enabled = url.isNotBlank(),
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
}
