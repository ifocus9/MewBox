package com.mewbox.tv.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.player.PlayerState
import com.mewbox.tv.player.Players
import com.mewbox.tv.ui.components.PanelAccent

/**
 * 播放器「更多」聚合面板：
 * 顶部 Tab 切换「播放设置」与「媒体信息」，将低频但必要的操作从底栏抽离出来保持播放视图克制整洁。
 */
@Composable
internal fun PlayerMoreDrawer(
    vodName: String,
    currentEpisodeName: String,
    playerState: PlayerState,
    opening: Long,
    ending: Long,
    isLandscape: Boolean,
    initialTab: Int = 0,
    onRefreshPlayback: () -> Unit,
    onOpenTracksDrawer: () -> Unit,
    onSelectResizeMode: (Int) -> Unit,
    onSetOpening: () -> Unit,
    onResetOpening: () -> Unit,
    onSetEnding: () -> Unit,
    onResetEnding: () -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember(initialTab) { mutableIntStateOf(initialTab.coerceIn(0, 1)) }

    PlayerDrawerShell(
        onDismiss = onDismiss,
        isLandscape = isLandscape,
    ) {
        Text(
            text = "更多设置",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )

        Spacer(modifier = Modifier.height(10.dp))

        PlayerDrawerTabBar(
            tabs = listOf("播放设置", "媒体信息"),
            selectedIndex = selectedTab,
            onSelect = { selectedTab = it },
        )

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedTab == 0) {
            PlaybackSettingsTab(
                playerState = playerState,
                opening = opening,
                ending = ending,
                isLandscape = isLandscape,
                onRefreshPlayback = {
                    onRefreshPlayback()
                    onDismiss()
                },
                onOpenTracksDrawer = onOpenTracksDrawer,
                onSelectResizeMode = onSelectResizeMode,
                onSetOpening = onSetOpening,
                onResetOpening = onResetOpening,
                onSetEnding = onSetEnding,
                onResetEnding = onResetEnding,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        } else {
            PlayerMediaInfoContent(
                vodName = vodName,
                currentEpisodeName = currentEpisodeName,
                videoWidth = playerState.videoWidth,
                videoHeight = playerState.videoHeight,
                headers = playerState.headers,
                playUrl = playerState.playUrl,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
        }
    }
}

@Composable
private fun PlaybackSettingsTab(
    playerState: PlayerState,
    opening: Long,
    ending: Long,
    isLandscape: Boolean,
    onRefreshPlayback: () -> Unit,
    onOpenTracksDrawer: () -> Unit,
    onSelectResizeMode: (Int) -> Unit,
    onSetOpening: () -> Unit,
    onResetOpening: () -> Unit,
    onSetEnding: () -> Unit,
    onResetEnding: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentAspect = PlayerAspectMode.fromResizeMode(playerState.resizeMode)

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 1. 快捷操作行（刷新播放 + 竖屏下的轨道直达入口）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            QuickActionTile(
                label = "刷新播放",
                icon = Icons.Rounded.Refresh,
                onClick = onRefreshPlayback,
                modifier = Modifier.weight(1f),
            )
            if (!isLandscape) {
                QuickActionTile(
                    label = "字幕/音轨",
                    icon = Icons.Rounded.Subtitles,
                    onClick = onOpenTracksDrawer,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // 2. 画面比例
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(title = "画面比例")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PlayerAspectMode.entries.forEach { mode ->
                    val selected = mode == currentAspect
                    OptionChip(
                        text = mode.label,
                        selected = selected,
                        onClick = { onSelectResizeMode(mode.resizeMode) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // 3. 跳过片头 / 片尾（直出“设当前 / 清除”按钮，避免隐晦长按操作）
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(title = "跳过片头 / 片尾")
            OpEdSettingRow(
                title = "片头",
                valueMs = opening,
                onSetCurrent = onSetOpening,
                onReset = onResetOpening,
            )
            OpEdSettingRow(
                title = "片尾",
                valueMs = ending,
                onSetCurrent = onSetEnding,
                onReset = onResetEnding,
            )
        }
    }
}

@Composable
private fun SectionLabel(title: String) {
    Text(
        text = title,
        fontSize = 12.5.sp,
        fontWeight = FontWeight.Medium,
        color = Color.White.copy(alpha = 0.6f),
    )
}

@Composable
private fun QuickActionTile(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.09f),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.height(42.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(17.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun OptionChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) PanelAccent.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) PanelAccent else Color.White.copy(alpha = 0.9f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun OpEdSettingRow(
    title: String,
    valueMs: Long,
    onSetCurrent: () -> Unit,
    onReset: () -> Unit,
) {
    val isSet = valueMs > 0L
    Surface(
        color = Color.White.copy(alpha = 0.07f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = if (isSet) Players.formatDuration(valueMs) else "未设置",
                    color = if (isSet) PanelAccent else Color.White.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PanelAccent.copy(alpha = 0.22f))
                        .clickable(onClick = onSetCurrent)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "设为当前",
                        color = PanelAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (isSet) {
                    Box(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.1f))
                            .clickable(onClick = onReset)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "清除",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
