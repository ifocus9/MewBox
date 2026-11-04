package com.mewbox.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mewbox.tv.bean.Config

private val TextPrimary = Color(0xFF1A1A1A)
private val TextSecondary = Color(0xFF8E8E93)
private val ItemBg = Color(0xFFF2F4F7)
private val ItemBorder = Color(0xFFE2E5EA)

/**
 * 订阅历史弹窗（对齐 MewPlayer HistoryDialog + ConfigAdapter）
 *
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
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .widthIn(max = 480.dp),
            shape = RoundedCornerShape(18.dp),
            color = Color.White,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
            ) {
                // 订阅列表
                if (configs.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "暂无数据",
                            fontSize = 14.sp,
                            color = TextSecondary,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(configs, key = { it.url }) { item ->
                            // 设置了名称只展示名称；未设置名称时以订阅链接作为名称展示
                            val hasCustomName = item.name.isNotBlank()
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = { onSelect(item.url) },
                                        onLongClick = onEdit?.let { callback -> { callback(item) } },
                                    ),
                                shape = RoundedCornerShape(10.dp),
                                color = ItemBg,
                                border = BorderStroke(0.6.dp, ItemBorder),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = item.desc,
                                        fontSize = if (hasCustomName) 14.5.sp else 13.sp,
                                        fontWeight = if (hasCustomName) FontWeight.SemiBold else FontWeight.Normal,
                                        color = TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )

                                    if (onDelete != null) {
                                        // 删除按钮（参考 MewPlayer ic_setting_delete）
                                        IconButton(
                                            onClick = { onDelete(item.url) },
                                            modifier = Modifier.size(32.dp),
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DeleteOutline,
                                                contentDescription = "删除订阅",
                                                modifier = Modifier.size(18.dp),
                                                tint = TextSecondary,
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
}
