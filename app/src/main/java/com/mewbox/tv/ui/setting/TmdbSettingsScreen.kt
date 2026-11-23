package com.mewbox.tv.ui.setting

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.util.AppToast

/**
 * TMDB 独立二级设置页：沿用设置页底色 + 圆角卡片风格，自动适配深浅色主题。
 */
@Composable
fun TmdbSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TmdbSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(state.message) {
        state.message?.let { msg ->
            AppToast.show(msg)
            viewModel.consumeMessage()
        }
    }

    LaunchedEffect(state.savedAndExit) {
        if (state.savedAndExit) {
            viewModel.consumeSavedAndExit()
            onBack()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TmdbTopBar(
            canSave = state.hasChanges && !state.saving,
            saving = state.saving,
            onBack = onBack,
            onSave = viewModel::save,
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // 卡片 1：总开关
            SettingCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    ) {
                        Text(
                            text = "启用 TMDB",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            color = TextPrimary,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = "开启后自动匹配影视海报、简介与评分",
                            fontSize = 12.5.sp,
                            color = TextSecondary,
                        )
                    }
                    Switch(
                        checked = state.enable,
                        onCheckedChange = viewModel::onEnableChange,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                            uncheckedThumbColor = if (MewBoxTheme.isDark) Color(0xFFD5D9E2) else Color.White,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            uncheckedBorderColor = Color.Transparent,
                        ),
                    )
                }
            }

            // 卡片 2：接口与代理配置
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingSectionLabel("接口与代理")
                SettingCard {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        TmdbInputField(
                            label = "API Key / Token",
                            value = state.apiKey,
                            onValueChange = viewModel::onApiKeyChange,
                            placeholder = "输入 v3 API Key 或 v4 读访问令牌",
                            isPassword = true,
                            passwordVisible = passwordVisible,
                            onTogglePasswordVisible = { passwordVisible = !passwordVisible },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            ),
                        )

                        TmdbInputField(
                            label = "API 代理地址",
                            optionalTag = "选填",
                            value = state.apiBase,
                            onValueChange = viewModel::onApiBaseChange,
                            placeholder = "留空默认使用 api.tmdb.org",
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Next,
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            ),
                        )

                        TmdbInputField(
                            label = "图片 CDN 代理",
                            optionalTag = "选填",
                            value = state.imageBase,
                            onValueChange = viewModel::onImageBaseChange,
                            placeholder = "留空默认使用 image.tmdb.org",
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
                        )
                    }
                }
            }

            // 卡片 3：连通性测试
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingSectionLabel("连通性测试")
                SettingCard {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        TmdbTestRow(
                            title = "测试 API 连通性",
                            subtitle = "验证 Key 与 API 地址是否可用",
                            status = state.apiTest,
                            onClick = viewModel::testApi,
                        )
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        )
                        TmdbTestRow(
                            title = "测试图片 CDN",
                            subtitle = "验证海报图片地址是否可访问",
                            status = state.imageTest,
                            onClick = viewModel::testImage,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun TmdbTopBar(
    canSave: Boolean,
    saving: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = if (canSave) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceContainerHighest
        },
        animationSpec = tween(durationMillis = 180),
        label = "saveBgColor",
    )
    val contentColor by animateColorAsState(
        targetValue = if (canSave) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        animationSpec = tween(durationMillis = 180),
        label = "saveTextColor",
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = TextPrimary,
            )
        }
        Text(
            text = "TMDB 设置",
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.height(34.dp),
            shape = RoundedCornerShape(17.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor,
                disabledContentColor = contentColor,
            ),
            elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
        ) {
            if (saving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = contentColor,
                )
            } else {
                Text(
                    text = "保存",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun TmdbInputField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    optionalTag: String? = null,
    isPassword: Boolean = false,
    passwordVisible: Boolean = true,
    onTogglePasswordVisible: (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    var isFocused by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val borderColor by animateColorAsState(
        targetValue = if (isFocused) primaryColor.copy(alpha = 0.55f) else Color.Transparent,
        animationSpec = tween(durationMillis = 160),
        label = "inputBorder",
    )

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
            )
            if (!optionalTag.isNullOrEmpty()) {
                Text(
                    text = optionalTag,
                    fontSize = 11.5.sp,
                    color = TextSecondary,
                )
            }
        }

        Spacer(Modifier.height(7.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 46.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
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
                        fontSize = 14.sp,
                        color = TextSecondary,
                        maxLines = 1,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontSize = 14.sp,
                        color = TextPrimary,
                    ),
                    cursorBrush = SolidColor(primaryColor),
                    visualTransformation = if (isPassword && !passwordVisible) {
                        PasswordVisualTransformation()
                    } else {
                        VisualTransformation.None
                    },
                    keyboardOptions = keyboardOptions,
                    keyboardActions = keyboardActions,
                )
            }

            if (isPassword && onTogglePasswordVisible != null) {
                IconButton(
                    onClick = onTogglePasswordVisible,
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Default.VisibilityOff
                        } else {
                            Icons.Default.Visibility
                        },
                        contentDescription = if (passwordVisible) "隐藏 Key" else "显示 Key",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (value.isNotEmpty()) {
                IconButton(
                    onClick = { onValueChange("") },
                    modifier = Modifier.size(34.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "清除",
                        tint = TextSecondary,
                        modifier = Modifier.size(17.dp),
                    )
                }
            } else if (!isPassword) {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

@Composable
private fun TmdbTestRow(
    title: String,
    subtitle: String,
    status: TmdbTestStatus,
    onClick: () -> Unit,
) {
    val isTesting = status is TmdbTestStatus.Testing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isTesting, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = title,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.5.sp,
                    color = TextSecondary,
                )
            }

            TmdbTestBadge(status = status)
        }

        val detailMessage = when (status) {
            is TmdbTestStatus.Success -> status.message
            is TmdbTestStatus.Error -> status.message
            TmdbTestStatus.Idle, TmdbTestStatus.Testing -> ""
        }

        AnimatedVisibility(
            visible = status is TmdbTestStatus.Success || status is TmdbTestStatus.Error,
            enter = fadeIn(tween(160)) + expandVertically(tween(180)),
            exit = fadeOut(tween(120)) + shrinkVertically(tween(150)),
        ) {
            val isSuccess = status is TmdbTestStatus.Success
            val extendedColors = MewBoxTheme.colors
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (isSuccess) extendedColors.successBg else extendedColors.errorBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = detailMessage,
                    fontSize = 12.5.sp,
                    color = if (isSuccess) extendedColors.successText else extendedColors.errorText,
                    lineHeight = 18.sp,
                )
            }
        }
    }
}

@Composable
private fun TmdbTestBadge(status: TmdbTestStatus) {
    val extendedColors = MewBoxTheme.colors
    val bgColor = when (status) {
        TmdbTestStatus.Idle, TmdbTestStatus.Testing -> MaterialTheme.colorScheme.primaryContainer
        is TmdbTestStatus.Success -> extendedColors.successBg
        is TmdbTestStatus.Error -> extendedColors.errorBg
    }
    val fgColor = when (status) {
        TmdbTestStatus.Idle, TmdbTestStatus.Testing -> MaterialTheme.colorScheme.onPrimaryContainer
        is TmdbTestStatus.Success -> extendedColors.successText
        is TmdbTestStatus.Error -> extendedColors.errorText
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        when (status) {
            TmdbTestStatus.Idle -> {
                Text(
                    text = "测试",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                )
            }
            TmdbTestStatus.Testing -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = fgColor,
                )
                Text(
                    text = "测试中",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                )
            }
            is TmdbTestStatus.Success -> {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "正常",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                )
            }
            is TmdbTestStatus.Error -> {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = fgColor,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "失败",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = fgColor,
                )
            }
        }
    }
}
