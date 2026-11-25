package com.mewbox.tv.ui.setting

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.api.config.SettingsRepository
import com.mewbox.tv.ui.components.LocalMewOverlayDismiss
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * 豆瓣想看 uid 设置底部抽屉：支持纯数字 uid 或个人主页链接（自动提取 people 段）。
 * 校验不通过时留在抽屉内提示，onSave 只会收到归一化后的合法 uid（空串代表清除）。
 */
@Composable
fun DoubanDialog(
    initialUid: String,
    onSave: (uid: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var uid by rememberSaveable(initialUid) { mutableStateOf(initialUid) }
    var errorText by rememberSaveable { mutableStateOf("") }
    var isFocused by remember { mutableStateOf(false) }
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MewBoxTheme.colors.errorText

    val borderColor by animateColorAsState(
        targetValue = when {
            errorText.isNotEmpty() -> errorColor
            isFocused -> primaryColor.copy(alpha = 0.55f)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 160),
        label = "doubanInputBorder",
    )

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
                .imePadding(),
        ) {
            Text(
                text = "豆瓣想看",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OverlayTokens.DialogTitle,
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "豆瓣个人 uid",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = OverlayTokens.TextPrimary,
            )

            Spacer(Modifier.height(7.dp))

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
                    if (uid.isEmpty()) {
                        Text(
                            text = "纯数字 uid 或个人主页链接",
                            color = OverlayTokens.TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                        )
                    }
                    BasicTextField(
                        value = uid,
                        onValueChange = {
                            uid = it
                            errorText = ""
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = OverlayTokens.TextPrimary,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(primaryColor),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused },
                    )
                }

                if (uid.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            uid = ""
                            errorText = ""
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "清除",
                            tint = OverlayTokens.TextSecondary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "电脑登录豆瓣 → 个人主页，地址栏 people/ 后面的数字即 uid；留空保存可清除。",
                fontSize = 12.sp,
                color = OverlayTokens.TextSecondary,
                lineHeight = 17.sp,
            )

            if (errorText.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = errorText,
                    fontSize = 12.sp,
                    color = errorColor,
                )
            }

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
                        val normalized = SettingsRepository.normalizeDoubanUid(uid)
                        if (normalized == null) {
                            errorText = "无法识别，请输入纯数字 uid 或个人主页链接"
                        } else {
                            onSave(normalized)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryColor,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    elevation = ButtonDefaults.buttonElevation(0.dp, 0.dp),
                ) {
                    Text("确定", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
