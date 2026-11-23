package com.mewbox.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.bean.Config

/**
 * 订阅历史底部抽屉
 * - 展示规则：设置了名称只展示名称；未设置名称时以订阅链接展示
 * - 当前激活的订阅由调用方过滤，列表内不出现
 * - [onEdit] / [onDelete] 为 null 时不展示对应能力（首页仅切换订阅的场景只传 [onSelect]）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SubscriptionHistoryDialog(
    configs: List<Config>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    onEdit: ((Config) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
) {
    val maxListHeight = (LocalConfiguration.current.screenHeightDp * 0.56f).dp.coerceIn(220.dp, 420.dp)

    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
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
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "切换订阅",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = OverlayTokens.DialogTitle,
                    )
                    if (configs.isNotEmpty()) {
                        Text(
                            text = "共 ${configs.size} 个",
                            fontSize = 12.5.sp,
                            color = OverlayTokens.TextSecondary,
                            modifier = Modifier.padding(bottom = 1.dp),
                        )
                    }
                }
                if (onEdit != null && configs.isNotEmpty()) {
                    Text(
                        text = "长按可编辑",
                        fontSize = 12.sp,
                        color = OverlayTokens.TextSecondary,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            if (configs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .padding(bottom = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "暂无数据",
                        fontSize = 14.sp,
                        color = OverlayTokens.TextSecondary,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxListHeight),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(configs, key = { it.url }) { item ->
                        val hasCustomName = item.name.isNotBlank()
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { onSelect(item.url) },
                                    onLongClick = onEdit?.let { callback -> { callback(item) } },
                                ),
                            shape = RoundedCornerShape(12.dp),
                            color = OverlayTokens.SheetItemBg,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 46.dp)
                                    .padding(
                                        start = 14.dp,
                                        end = if (onDelete != null) 6.dp else 14.dp,
                                        top = 10.dp,
                                        bottom = 10.dp,
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.desc,
                                    fontSize = if (hasCustomName) 14.5.sp else 13.5.sp,
                                    fontWeight = if (hasCustomName) FontWeight.SemiBold else FontWeight.Normal,
                                    color = OverlayTokens.TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )

                                if (onDelete != null) {
                                    IconButton(
                                        onClick = { onDelete(item.url) },
                                        modifier = Modifier.size(32.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.DeleteOutline,
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
