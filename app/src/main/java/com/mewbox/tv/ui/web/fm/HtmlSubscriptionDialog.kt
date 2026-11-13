package com.mewbox.tv.ui.web.fm

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
private val AccentBlue = Color(0xFF0A7AFF)
private val ActiveItemBg = Color(0xFFEEF6FF)
private val ActiveItemBorder = Color(0xFF99C8FF)
private val ItemBg = Color(0xFFF2F4F7)
private val ItemBorder = Color(0xFFE2E5EA)

/**
 * HTML 沉浸式页面专用的订阅管理与切换弹窗：
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "订阅切换",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary,
                        modifier = Modifier.weight(1f),
                    )

                    Surface(
                        onClick = onAdd,
                        shape = RoundedCornerShape(8.dp),
                        color = ItemBg,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = "添加订阅",
                                tint = AccentBlue,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "添加",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = AccentBlue,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Surface(
                        onClick = onOpenSettings,
                        shape = RoundedCornerShape(8.dp),
                        color = ItemBg,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Settings,
                                contentDescription = "设置",
                                tint = TextPrimary,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "设置",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (configs.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(110.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "暂无订阅记录",
                            fontSize = 14.sp,
                            color = TextSecondary,
                        )
                    }
                } else {
                    // 当前激活订阅置顶展示
                    val sortedConfigs = configs.sortedByDescending { it.url == activeConfigUrl }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(sortedConfigs, key = { it.url }) { item ->
                            val isActive = item.url == activeConfigUrl
                            val hasCustomName = item.name.isNotBlank()

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            if (!isActive) {
                                                onSelect(item.url)
                                            } else {
                                                onDismiss()
                                            }
                                        },
                                        onLongClick = { onEdit(item) },
                                    ),
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) ActiveItemBg else ItemBg,
                                border = BorderStroke(
                                    width = if (isActive) 1.dp else 0.6.dp,
                                    color = if (isActive) ActiveItemBorder else ItemBorder,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(
                                                        if (item.isHtml) AccentBlue.copy(alpha = 0.14f)
                                                        else Color(0x14000000)
                                                    )
                                                    .padding(horizontal = 5.dp, vertical = 1.dp),
                                            ) {
                                                Text(
                                                    text = if (item.isHtml) "网页" else "点播",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Medium,
                                                    color = if (item.isHtml) AccentBlue else TextSecondary,
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = item.desc,
                                                fontSize = if (hasCustomName || isActive) 14.5.sp else 13.sp,
                                                fontWeight = if (hasCustomName || isActive) FontWeight.SemiBold else FontWeight.Normal,
                                                color = if (isActive) AccentBlue else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (hasCustomName) {
                                            Text(
                                                text = item.url,
                                                fontSize = 11.5.sp,
                                                color = TextSecondary,
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
                                            tint = TextSecondary,
                                        )
                                    }

                                    if (!isActive) {
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

