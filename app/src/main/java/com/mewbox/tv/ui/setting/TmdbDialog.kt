package com.mewbox.tv.ui.setting

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mewbox.tv.bean.TmdbConfig
import kotlinx.coroutines.launch

private val BluePrimary = Color(0xFF217AF4)
private val DialogOutlinedBg = Color(0xFFF2F4F7)
private val DialogOutlinedText = Color(0xFF4E5564)

private enum class TestState {
    None, Testing, Success, Error
}

/**
 * TMDB 设置弹窗
 * 参考 MewPlayer dialog_tmdb.xml 及 TmdbDialog.java
 */
@Composable
fun TmdbDialog(
    initialConfig: TmdbConfig,
    onTestApi: suspend (apiKey: String, apiBase: String) -> Result<String>,
    onTestImage: suspend (imageBase: String) -> Result<String>,
    onSave: (config: TmdbConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var enable by rememberSaveable(initialConfig.enable) { mutableStateOf(initialConfig.enable) }
    var apiKey by rememberSaveable(initialConfig.apiKey) { mutableStateOf(initialConfig.apiKey) }
    var apiBase by rememberSaveable(initialConfig.apiBase) { mutableStateOf(initialConfig.apiBase) }
    var imageBase by rememberSaveable(initialConfig.imageBase) { mutableStateOf(initialConfig.imageBase) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    var testState by rememberSaveable { mutableStateOf(TestState.None) }
    var testResultText by rememberSaveable { mutableStateOf("") }
    var isTesting by rememberSaveable { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

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
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 标题
                Text(
                    text = "TMDB 设置",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF202124),
                )

                Spacer(Modifier.height(14.dp))

                // 总开关（参考 enableSwitch）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "启用 TMDB",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1A1A1A),
                    )
                    Switch(
                        checked = enable,
                        onCheckedChange = { enable = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BluePrimary,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFE0E0E0),
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }

                Spacer(Modifier.height(10.dp))

                // API Key / Token 输入框
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API Key / Token", fontSize = 13.sp) },
                    placeholder = {
                        Text(
                            text = "输入 API Key（v3）或 读访问令牌（v4 Token）",
                            color = Color(0xFF8A8F98),
                            fontSize = 12.sp,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (passwordVisible) "隐藏 Key" else "显示 Key",
                                    tint = Color(0xFF8E8E93),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (apiKey.isNotEmpty()) {
                                IconButton(onClick = { apiKey = "" }) {
                                    Icon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = "清除",
                                        tint = Color(0xFF8E8E93),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
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

                // API 代理 Base URL 输入框
                OutlinedTextField(
                    value = apiBase,
                    onValueChange = { apiBase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("API 代理地址", fontSize = 13.sp) },
                    placeholder = {
                        Text(
                            text = "留空使用官方 api.tmdb.org",
                            color = Color(0xFF8A8F98),
                            fontSize = 13.sp,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        if (apiBase.isNotEmpty()) {
                            IconButton(onClick = { apiBase = "" }) {
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

                Spacer(Modifier.height(10.dp))

                // 图片 CDN 代理 Base URL 输入框
                OutlinedTextField(
                    value = imageBase,
                    onValueChange = { imageBase = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("图片 CDN 代理", fontSize = 13.sp) },
                    placeholder = {
                        Text(
                            text = "留空使用官方 image.tmdb.org",
                            color = Color(0xFF8A8F98),
                            fontSize = 13.sp,
                        )
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        if (imageBase.isNotEmpty()) {
                            IconButton(onClick = { imageBase = "" }) {
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

                Spacer(Modifier.height(14.dp))

                // 测试连通性按钮行（参考 btnTestApi & btnTestImage）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            if (apiKey.trim().isEmpty()) {
                                testState = TestState.Error
                                testResultText = "请先输入 API Key 或 Token"
                                return@OutlinedButton
                            }
                            isTesting = true
                            testState = TestState.Testing
                            testResultText = "测试中…"
                            scope.launch {
                                val res = onTestApi(apiKey.trim(), apiBase.trim())
                                isTesting = false
                                res.onSuccess { msg ->
                                    testState = TestState.Success
                                    testResultText = msg
                                }.onFailure { err ->
                                    testState = TestState.Error
                                    testResultText = err.message ?: "连接失败"
                                }
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (!isTesting) BluePrimary else Color(0xFFDADCE0)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BluePrimary,
                            disabledContentColor = Color(0xFF8E8E93),
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("测试 API 连通性", fontSize = 13.sp, maxLines = 1)
                    }

                    OutlinedButton(
                        onClick = {
                            isTesting = true
                            testState = TestState.Testing
                            testResultText = "测试中…"
                            scope.launch {
                                val res = onTestImage(imageBase.trim())
                                isTesting = false
                                res.onSuccess { msg ->
                                    testState = TestState.Success
                                    testResultText = msg
                                }.onFailure { err ->
                                    testState = TestState.Error
                                    testResultText = err.message ?: "图片 CDN 连接失败"
                                }
                            }
                        },
                        enabled = !isTesting,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, if (!isTesting) BluePrimary else Color(0xFFDADCE0)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = BluePrimary,
                            disabledContentColor = Color(0xFF8E8E93),
                        ),
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                    ) {
                        Text("测试图片 CDN", fontSize = 13.sp, maxLines = 1)
                    }
                }

                // 测试结果提示（参考 testResultText）
                if (testState != TestState.None && testResultText.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (testState == TestState.Testing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFF1E88E5),
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = testResultText,
                            fontSize = 13.sp,
                            color = when (testState) {
                                TestState.Success -> Color(0xFF2E7D32)
                                TestState.Error -> Color(0xFFD32F2F)
                                TestState.Testing -> Color(0xFF1E88E5)
                                TestState.None -> Color.Transparent
                            },
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                Spacer(Modifier.height(18.dp))

                // 底部操作按钮：取消与确定
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
                            onSave(
                                TmdbConfig(
                                    enable = enable,
                                    apiKey = apiKey.trim(),
                                    apiBase = apiBase.trim(),
                                    imageBase = imageBase.trim(),
                                )
                            )
                        },
                        modifier = Modifier.height(40.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = BluePrimary,
                            contentColor = Color.White,
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
