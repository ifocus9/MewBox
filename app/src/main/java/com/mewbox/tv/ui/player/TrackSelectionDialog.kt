package com.mewbox.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.C
import com.mewbox.tv.player.PlayerTrack

/**
 * 播放器轨道切换弹窗（字幕、音轨、视轨）
 * 遵循项目一贯的现代深色磨砂微透设计系统，对齐 FongMi TrackDialog 交互
 */
@Composable
internal fun TrackSelectionDialog(
    type: Int,
    tracks: List<PlayerTrack>,
    isTypeDisabled: Boolean = false,
    onSelectTrack: (PlayerTrack) -> Unit,
    onDisableTrack: () -> Unit,
    onResetToDefault: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (type) {
        C.TRACK_TYPE_TEXT -> "字幕选择"
        C.TRACK_TYPE_AUDIO -> "音轨选择"
        C.TRACK_TYPE_VIDEO -> "视轨清晰度"
        else -> "轨道选择"
    }

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isLand = screenWidthDp > 600

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLand) 0.50f else 0.88f)
                .fillMaxHeight(0.72f)
                .padding(vertical = 20.dp),
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF211F26),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                // 顶部标题栏
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "关闭",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // 轨道选择列表
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (type == C.TRACK_TYPE_TEXT) {
                        // 字幕选项：首项为【关闭字幕】
                        TrackItemRow(
                            title = "关闭字幕",
                            subtitle = if (isTypeDisabled) "当前已关闭字幕展示" else "不显示任何字幕",
                            isSelected = isTypeDisabled,
                            onClick = {
                                onDisableTrack()
                                onDismiss()
                            },
                        )
                    } else {
                        // 音频/视频：首项为【默认（自动）】
                        val isAnySelected = tracks.any { it.isSelected }
                        TrackItemRow(
                            title = "默认（自动推荐）",
                            subtitle = if (type == C.TRACK_TYPE_VIDEO) "跟随源站或自适应码率" else "系统自适应音频流",
                            isSelected = !isAnySelected,
                            onClick = {
                                onResetToDefault()
                                onDismiss()
                            },
                        )
                    }

                    if (tracks.isEmpty()) {
                        val emptyMsg = when (type) {
                            C.TRACK_TYPE_TEXT -> "当前视频未检测到外挂或内嵌字幕轨"
                            C.TRACK_TYPE_AUDIO -> "当前视频仅检测到单条默认音轨"
                            C.TRACK_TYPE_VIDEO -> "当前视频仅检测到单一清晰度流"
                            else -> "暂无可用轨道"
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = emptyMsg,
                                fontSize = 14.sp,
                                color = Color.White.copy(alpha = 0.5f),
                            )
                        }
                    } else {
                        tracks.forEach { track ->
                            val selected = if (type == C.TRACK_TYPE_TEXT) {
                                !isTypeDisabled && track.isSelected
                            } else {
                                track.isSelected
                            }
                            TrackItemRow(
                                title = track.name,
                                subtitle = track.details,
                                isSupported = track.isSupported,
                                isSelected = selected,
                                onClick = {
                                    if (!track.isSupported) {
                                        com.mewbox.tv.util.AppToast.show("当前设备暂不支持解码此音轨 (${track.name})")
                                        return@TrackItemRow
                                    }
                                    onSelectTrack(track)
                                    onDismiss()
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackItemRow(
    title: String,
    subtitle: String? = null,
    isSupported: Boolean = true,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val activeBg = when {
        isSelected -> Color(0x334CAF50)
        !isSupported -> Color(0x0AFFFFFF)
        else -> Color(0x11FFFFFF)
    }

    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)),
        color = activeBg,
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = when {
                            isSelected -> Color(0xFF81C784)
                            !isSupported -> Color.White.copy(alpha = 0.45f)
                            else -> Color.White
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )

                    if (!isSupported) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0x26FF5252),
                        ) {
                            Text(
                                text = "设备不支持",
                                color = Color(0xFFFF6D6D),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
                            )
                        }
                    }
                }

                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        color = when {
                            isSelected -> Color(0xFF81C784).copy(alpha = 0.8f)
                            !isSupported -> Color.White.copy(alpha = 0.35f)
                            else -> Color.White.copy(alpha = 0.6f)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isSelected) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(Color(0xFF81C784), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "已选择",
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

