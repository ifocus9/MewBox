package com.mewbox.tv.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.ui.theme.MewBoxTheme
import com.mewbox.tv.util.AppToast

/**
 * 播放器媒体与直链信息内容体，嵌入 [PlayerMoreDrawer] 的「媒体信息」Tab 中，
 * 支持单击或长按复制片名、请求头与播放地址。
 */
@Composable
internal fun PlayerMediaInfoContent(
    vodName: String,
    currentEpisodeName: String,
    videoWidth: Int,
    videoHeight: Int,
    headers: Map<String, String>,
    playUrl: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val displayTitle = remember(vodName, currentEpisodeName) {
        if (currentEpisodeName.isNotBlank()) {
            "$vodName · $currentEpisodeName"
        } else {
            vodName.ifBlank { "未知影片" }
        }
    }
    val headerText = remember(headers) {
        if (headers.isEmpty()) {
            ""
        } else {
            headers.entries.joinToString("\n") { (k, v) -> "$k : $v" }
        }
    }
    val displayUrl = remember(playUrl) {
        if (playUrl.startsWith("data")) {
            playUrl.take(128) + "..."
        } else {
            playUrl
        }
    }
    val resolutionBadge = remember(videoWidth, videoHeight) {
        formatResolutionBadge(videoWidth, videoHeight)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 片名 + 分辨率规格卡片
        Surface(
            color = Color.White.copy(alpha = 0.07f),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .copyOnTapOrLongPress(
                    context = context,
                    label = "Title",
                    textToCopy = displayTitle,
                    toastMessage = "已复制片名",
                ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "影片标题",
                        fontSize = 11.5.sp,
                        color = Color.White.copy(alpha = 0.56f),
                    )
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "点击复制",
                        tint = Color.White.copy(alpha = 0.45f),
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayTitle,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    lineHeight = 20.sp,
                )
                if (videoWidth > 0 && videoHeight > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (resolutionBadge != null) {
                            MediaSpecTag(text = resolutionBadge)
                        }
                        MediaSpecTag(text = "${videoWidth}×${videoHeight}")
                    }
                }
            }
        }

        if (displayUrl.isNotBlank()) {
            Surface(
                color = Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .copyOnTapOrLongPress(
                        context = context,
                        label = "URL",
                        textToCopy = playUrl,
                        toastMessage = "已复制播放地址",
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "播放地址",
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.56f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "点击复制",
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = displayUrl,
                        fontSize = 12.5.sp,
                        color = MewBoxTheme.colors.accentOnDark,
                        textDecoration = TextDecoration.Underline,
                        lineHeight = 18.sp,
                    )
                }
            }
        }

        if (headerText.isNotBlank()) {
            Surface(
                color = Color.White.copy(alpha = 0.07f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .copyOnTapOrLongPress(
                        context = context,
                        label = "Header",
                        textToCopy = headerText,
                        toastMessage = "已复制请求头",
                    ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "请求头",
                            fontSize = 11.5.sp,
                            color = Color.White.copy(alpha = 0.56f),
                        )
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = "点击复制",
                            tint = Color.White.copy(alpha = 0.45f),
                            modifier = Modifier.size(14.dp),
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = headerText,
                        fontSize = 12.5.sp,
                        color = Color(0xFFDDDDDD),
                        lineHeight = 18.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaSpecTag(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            color = Color.White.copy(alpha = 0.88f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun Modifier.copyOnTapOrLongPress(
    context: Context,
    label: String,
    textToCopy: String,
    toastMessage: String,
): Modifier = pointerInput(textToCopy) {
    val copyAction = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText(label, textToCopy))
        AppToast.show(toastMessage)
    }
    detectTapGestures(
        onTap = { copyAction() },
        onLongPress = { copyAction() },
    )
}
