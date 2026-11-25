package com.mewbox.tv.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import com.mewbox.tv.bean.PlayUrl
import com.mewbox.tv.player.PlayerTrack
import com.mewbox.tv.ui.components.PanelAccent
import com.mewbox.tv.util.AppToast
import kotlin.math.abs

/**
 * 播放器源画质切换面板（4K / 1080P / 原画等，对应源返回的 PlayUrl 列表）
 */
@Composable
internal fun QualitySelectionDialog(
    playUrls: List<PlayUrl>,
    selectedPlayUrl: PlayUrl?,
    isLandscape: Boolean,
    onSelectPlayUrl: (PlayUrl) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerDrawerShell(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
    ) {
        Text(
            text = "画质选择",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            playUrls.forEach { item ->
                val isSelected = item == selectedPlayUrl || item.url == selectedPlayUrl?.url
                DrawerOptionRow(
                    title = item.name,
                    isSelected = isSelected,
                    onClick = {
                        onSelectPlayUrl(item)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * 播放器倍速切换面板
 */
@Composable
internal fun SpeedSelectionDrawer(
    currentSpeed: Float,
    isLandscape: Boolean,
    onSelectSpeed: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    PlayerDrawerShell(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
    ) {
        Text(
            text = "播放倍速",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PLAYER_SPEED_OPTIONS.forEach { speed ->
                val isSelected = abs(currentSpeed - speed) < 0.02f
                DrawerOptionRow(
                    title = formatSpeedLabel(speed),
                    subtitle = null,
                    isSelected = isSelected,
                    onClick = {
                        onSelectSpeed(speed)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/**
 * 三合一轨道切换抽屉（字幕 / 音轨 / 视轨）。
 *
 * 顶部通过胶囊 Tab bar 切换轨道类型，方便在同一面板中连切字幕与音轨无需反复开关。
 */
@Composable
internal fun UnifiedTrackDrawer(
    subtitleTracks: List<PlayerTrack>,
    audioTracks: List<PlayerTrack>,
    videoTracks: List<PlayerTrack>,
    isSubtitleDisabled: Boolean,
    initialTab: Int = 0,
    isLandscape: Boolean,
    onSelectTrack: (PlayerTrack) -> Unit,
    onDisableSubtitle: () -> Unit,
    onResetToDefault: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 2)) }

    val tabs = remember(subtitleTracks.size, audioTracks.size, videoTracks.size) {
        listOf(
            if (subtitleTracks.isNotEmpty()) "字幕(${subtitleTracks.size})" else "字幕",
            if (audioTracks.isNotEmpty()) "音轨(${audioTracks.size})" else "音轨",
            if (videoTracks.isNotEmpty()) "视轨(${videoTracks.size})" else "视轨",
        )
    }

    val trackType = when (selectedTab) {
        0 -> C.TRACK_TYPE_TEXT
        1 -> C.TRACK_TYPE_AUDIO
        else -> C.TRACK_TYPE_VIDEO
    }
    val tracks = when (selectedTab) {
        0 -> subtitleTracks
        1 -> audioTracks
        else -> videoTracks
    }

    PlayerDrawerShell(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
    ) {
        Text(
            text = "轨道设置",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )

        Spacer(modifier = Modifier.height(10.dp))

        PlayerDrawerTabBar(
            tabs = tabs,
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (trackType == C.TRACK_TYPE_TEXT) {
                DrawerOptionRow(
                    title = "关闭字幕",
                    subtitle = if (isSubtitleDisabled) "当前已关闭字幕展示" else "不显示任何字幕",
                    isSelected = isSubtitleDisabled,
                    onClick = {
                        onDisableSubtitle()
                        AppToast.show("已关闭字幕")
                    },
                )
            } else {
                val isAnySelected = tracks.any { it.isSelected }
                DrawerOptionRow(
                    title = "默认（自动推荐）",
                    subtitle = if (trackType == C.TRACK_TYPE_VIDEO) "跟随源站或自适应码率" else "系统自适应音频流",
                    isSelected = !isAnySelected,
                    onClick = {
                        onResetToDefault(trackType)
                        AppToast.show("已恢复系统自适应")
                    },
                )
            }

            if (tracks.isEmpty()) {
                val emptyMsg = when (trackType) {
                    C.TRACK_TYPE_TEXT -> "当前视频未检测到外挂或内嵌字幕轨"
                    C.TRACK_TYPE_AUDIO -> "当前视频仅检测到单条默认音轨"
                    else -> "当前视频仅检测到单一清晰度流"
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = emptyMsg,
                        fontSize = 13.5.sp,
                        color = Color.White.copy(alpha = 0.52f),
                    )
                }
            } else {
                tracks.forEach { track ->
                    val selected = if (trackType == C.TRACK_TYPE_TEXT) {
                        !isSubtitleDisabled && track.isSelected
                    } else {
                        track.isSelected
                    }
                    DrawerOptionRow(
                        title = track.name,
                        subtitle = track.details,
                        isSupported = track.isSupported,
                        isSelected = selected,
                        onClick = {
                            if (!track.isSupported) {
                                AppToast.show("当前设备暂不支持解码此轨道 (${track.name})")
                                return@DrawerOptionRow
                            }
                            onSelectTrack(track)
                            AppToast.show("已切换：${track.name}")
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun DrawerOptionRow(
    title: String,
    subtitle: String? = null,
    isSupported: Boolean = true,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val activeBg = when {
        isSelected -> PanelAccent.copy(alpha = 0.24f)
        !isSupported -> Color.White.copy(alpha = 0.04f)
        else -> Color.White.copy(alpha = 0.08f)
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
                .padding(horizontal = 14.dp, vertical = 10.dp),
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
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            isSelected -> PanelAccent
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
                        fontSize = 11.5.sp,
                        color = when {
                            isSelected -> PanelAccent.copy(alpha = 0.82f)
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
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = "已选择",
                    tint = PanelAccent,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
