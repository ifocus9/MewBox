package com.mewbox.tv.ui.setting

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mewbox.tv.api.config.SettingsRepository

private val BluePrimary = Color(0xFF217AF4)

/**
 * 豆瓣想看 uid 设置弹窗：支持纯数字 uid 或个人主页链接（自动提取 people 段）。
 * 校验不通过时留在弹窗内提示，onSave 只会收到归一化后的合法 uid（空串代表清除）。
 */
@Composable
fun DoubanDialog(
    initialUid: String,
    onSave: (uid: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var uid by rememberSaveable(initialUid) { mutableStateOf(initialUid) }
    var errorText by rememberSaveable { mutableStateOf("") }

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
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "豆瓣想看",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124),
                )

                Spacer(Modifier.height(14.dp))

                OutlinedTextField(
                    value = uid,
                    onValueChange = {
                        uid = it
                        errorText = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("豆瓣个人 uid", fontSize = 13.sp) },
                    placeholder = {
                        Text(
                            text = "纯数字 uid 或个人主页链接",
                            color = Color(0xFF8A8F98),
                            fontSize = 13.sp,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        if (uid.isNotEmpty()) {
                            IconButton(onClick = { uid = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "清除",
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

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "电脑登录豆瓣 → 个人主页，地址栏 people/ 后面的数字即 uid；留空保存可清除。",
                    fontSize = 12.sp,
                    color = Color(0xFF8A8F98),
                )

                if (errorText.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = errorText,
                        fontSize = 12.sp,
                        color = Color(0xFFD32F2F),
                    )
                }

                Spacer(Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BluePrimary,
                        ),
                        border = BorderStroke(1.dp, BluePrimary),
                    ) {
                        Text("取消", fontSize = 15.sp)
                    }

                    Spacer(Modifier.width(10.dp))

                    Button(
                        onClick = {
                            val normalized = SettingsRepository.normalizeDoubanUid(uid)
                            if (normalized == null) {
                                errorText = "无法识别，请输入纯数字 uid 或个人主页链接"
                            } else {
                                onSave(normalized)
                            }
                        },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BluePrimary,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("确定", fontSize = 15.sp)
                    }
                }
            }
        }
    }
}
