package com.mewbox.tv.ui.player

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 播放器媒体与直链信息弹窗
 * 支持单击或长按复制片名、请求头与播放地址
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

    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val isLand = screenWidthDp > 600

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isLand) 0.62f else 0.92f)
                .padding(vertical = 24.dp),
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFF211F26),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(
                    text = displayTitle,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White,
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
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = headerText,
                        fontSize = 14.sp,
                        color = Color(0xFFDDDDDD),
                        lineHeight = 20.sp,
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
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = displayUrl,
                        fontSize = 14.sp,
                        color = Color(0xFF64B5F6),
                        textDecoration = TextDecoration.Underline,
                        lineHeight = 20.sp,
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
        Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
    }
    detectTapGestures(
        onTap = { copyAction() },
        onLongPress = { copyAction() },
    )
}

