package com.mewbox.tv.ui.web.fm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Config
import com.mewbox.tv.ui.components.LocalMewOverlayDismiss
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens
import com.mewbox.tv.ui.setting.FilePickerHelper
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * HTML 沉浸式页面专用的订阅管理与切换底部抽屉：
 * - 清晰展示当前正在使用的订阅（支持点击编辑图标或长按修改名称/地址）
 * - 展示其余全部订阅列表，点击即可一键切换（切回普通 VOD 订阅即自动回到原生首页）
 * - 支持直接新增订阅或进入应用设置页
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HtmlSubscriptionDialog(
    configs: List<Config>,
    activeConfigUrl: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Config) -> Unit,
    onDelete: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp.coerceIn(220.dp, 420.dp)

    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        val dismiss = LocalMewOverlayDismiss.current ?: onDismiss
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .padding(horizontal = OverlayTokens.Padding)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "订阅切换",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OverlayTokens.DialogTitle,
                    modifier = Modifier.weight(1f),
                )

                Surface(
                    onClick = onAdd,
                    shape = RoundedCornerShape(10.dp),
                    color = OverlayTokens.SheetItemBg,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "添加订阅",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "添加",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Surface(
                    onClick = onOpenSettings,
                    shape = RoundedCornerShape(10.dp),
                    color = OverlayTokens.SheetItemBg,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = "设置",
                            tint = OverlayTokens.TextPrimary,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "设置",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = OverlayTokens.TextPrimary,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无订阅记录",
                        fontSize = 14.sp,
                        color = OverlayTokens.TextSecondary,
                    )
                }
            } else {
                val sortedConfigs = configs.sortedByDescending { it.url == activeConfigUrl }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(sortedConfigs, key = { it.url }) { item ->
                        val isActive = item.url == activeConfigUrl
                        val hasCustomName = item.name.isNotBlank()

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = {
                                        if (!isActive) {
                                            onSelect(item.url)
                                        } else {
                                            dismiss()
                                        }
                                    },
                                    onLongClick = { onEdit(item) },
                                ),
                            shape = RoundedCornerShape(12.dp),
                            color = if (isActive) MaterialTheme.colorScheme.primaryContainer else OverlayTokens.SheetItemBg,
                            border = if (isActive) BorderStroke(0.8.dp, MewBoxTheme.colors.primaryBorder) else null,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    if (item.isHtml) MewBoxTheme.colors.primarySoftBg
                                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                                )
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        ) {
                                            Text(
                                                text = if (item.isHtml) "网页" else "点播",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = if (item.isHtml) {
                                                    MaterialTheme.colorScheme.onPrimaryContainer
                                                } else {
                                                    OverlayTokens.TextSecondary
                                                },
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = item.desc,
                                            fontSize = if (hasCustomName || isActive) 14.5.sp else 13.5.sp,
                                            fontWeight = if (hasCustomName || isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            color = if (isActive) {
                                                MaterialTheme.colorScheme.onPrimaryContainer
                                            } else {
                                                OverlayTokens.TextPrimary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (hasCustomName) {
                                        val context = LocalContext.current
                                        Text(
                                            text = formatSubscriptionSubtitle(context, item),
                                            fontSize = 11.5.sp,
                                            color = OverlayTokens.TextSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = { onEdit(item) },
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Edit,
                                        contentDescription = "编辑订阅",
                                        modifier = Modifier.size(16.dp),
                                        tint = OverlayTokens.TextSecondary,
                                    )
                                }

                                if (!isActive) {
                                    IconButton(
                                        onClick = { onDelete(item.url) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.DeleteOutline,
                                            contentDescription = "删除订阅",
                                            modifier = Modifier.size(18.dp),
                                            tint = OverlayTokens.TextSecondary,
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
}

private fun formatSubscriptionSubtitle(context: android.content.Context, item: Config): String {
    val trimmed = item.url.trim()
    return when {
        trimmed.startsWith("local://node/", ignoreCase = true) -> "本地 Node.js 压缩包"
        trimmed.startsWith("local://vod/", ignoreCase = true) -> "本地点播配置文件"
        FilePickerHelper.isInternalLocalConfigUrl(context, trimmed) -> "本地网页订阅文件"
        else -> trimmed
    }
}
