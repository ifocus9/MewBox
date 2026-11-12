package com.mewbox.tv.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mewbox.tv.api.tmdb.TmdbClient

private const val BackdropScale = 1.3f
private val BackdropBlurRadius = 40.dp
private val BackdropScrimAlphas = listOf(0.22f, 0.48f, 0.80f)

/**
 * 大海报虚化背景层（完全移植 AVBox PosterBackdrop）
 * 放大 1.3 倍 + 40dp 高斯模糊 + 竖向三段式 surface 遮罩渐变
 */
@Composable
fun PosterBackdrop(
    pic: String?,
    modifier: Modifier = Modifier,
    onImage: ((coil3.Image) -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize()) {
        var currentPic by remember(pic) { mutableStateOf(pic) }
        var hasFallenBack by remember(pic) { mutableStateOf(false) }
        var loadFailed by remember(pic) { mutableStateOf(false) }

        if (!currentPic.isNullOrEmpty() && !loadFailed) {
            AsyncImage(
                model = currentPic,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onState = { state ->
                    if (state is coil3.compose.AsyncImagePainter.State.Success) {
                        onImage?.invoke(state.result.image)
                    } else if (state is coil3.compose.AsyncImagePainter.State.Error) {
                        val fallback = currentPic?.let { TmdbClient.resolveFallbackImageUrl(it) }
                        if (!hasFallenBack && fallback != null && fallback != currentPic) {
                            hasFallenBack = true
                            currentPic = fallback
                        } else {
                            loadFailed = true
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = BackdropScale
                        scaleY = BackdropScale
                    }
                    .blur(BackdropBlurRadius),
            )
        }

        if (currentPic.isNullOrEmpty() || loadFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                Color(0xFF1E2430),
                                Color(0xFF12151C),
                                Color(0xFF090A0D),
                            ),
                        ),
                    ),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = BackdropScrimAlphas.map {
                            MaterialTheme.colorScheme.surface.copy(alpha = it)
                        },
                    ),
                ),
        )
    }
}
