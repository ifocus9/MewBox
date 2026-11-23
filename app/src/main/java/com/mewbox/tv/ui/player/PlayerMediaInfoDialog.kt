package com.mewbox.tv.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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
 * 播放器媒体与直链信息右侧抽屉
 * 与画质、轨道、选集抽屉共用 [PlayerDrawerShell]，支持单击或长按复制片名、请求头与播放地址
 */
@Composable
internal fun PlayerMediaInfoDialog(
    vodName: String,
    currentEpisodeName: String,
    headers: Map<String, String>,
    playUrl: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current

    val displayTitle = remember(vodName, currentEpisodeName) {
        if (currentEpisodeName.isNotBlank()) {
            "$vodName: $currentEpisodeName"
        } else {
            vodName
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

    PlayerDrawerShell(onDismiss = onDismiss) {
        Text(
            text = "媒体信息",
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )

        Spacer(modifier = Modifier.height(12.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = displayTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                lineHeight = 22.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .copyOnTapOrLongPress(
                        context = context,
                        label = "Title",
                        textToCopy = displayTitle,
                        toastMessage = "已复制片名",
                    ),
            )

            if (headerText.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "请求头",
                    fontSize = 12.sp,
                    color = Color(0x99FFFFFF),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = headerText,
                    fontSize = 13.sp,
                    color = Color(0xFFDDDDDD),
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .copyOnTapOrLongPress(
                            context = context,
                            label = "Header",
                            textToCopy = headerText,
                            toastMessage = "已复制请求头",
                        ),
                )
            }

            if (displayUrl.isNotBlank()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "播放地址",
                    fontSize = 12.sp,
                    color = Color(0x99FFFFFF),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = displayUrl,
                    fontSize = 13.sp,
                    color = MewBoxTheme.colors.accentOnDark,
                    textDecoration = TextDecoration.Underline,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .copyOnTapOrLongPress(
                            context = context,
                            label = "URL",
                            textToCopy = playUrl,
                            toastMessage = "已复制播放地址",
                        ),
                )
            }
        }
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
