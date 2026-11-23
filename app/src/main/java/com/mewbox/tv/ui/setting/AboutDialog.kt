package com.mewbox.tv.ui.setting

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mewbox.tv.BuildConfig
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
) {
    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .padding(horizontal = OverlayTokens.Padding)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Text(
                text = "关于",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = OverlayTokens.DialogTitle,
            )

            Spacer(Modifier.height(14.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = OverlayTokens.SheetItemBg,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Text(
                        text = "MewBox",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                    )

                    Spacer(Modifier.height(6.dp))

                    Text(
                        text = "版本：v${BuildConfig.VERSION_NAME}",
                        fontSize = 13.5.sp,
                        color = TextSecondary,
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = "架构：arm64-v8a",
                        fontSize = 13.5.sp,
                        color = TextSecondary,
                    )
                }
            }
        }
    }
}
