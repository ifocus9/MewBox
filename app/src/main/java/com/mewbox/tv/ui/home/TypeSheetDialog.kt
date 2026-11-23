package com.mewbox.tv.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.capsule.ContinuousCapsule
import com.mewbox.tv.bean.VodFilter
import com.mewbox.tv.bean.VodFilterValue
import com.mewbox.tv.ui.components.MewOverlayVariant
import com.mewbox.tv.ui.components.MewSheetShell
import com.mewbox.tv.ui.components.OverlayTokens
import com.mewbox.tv.ui.theme.MewBoxTheme

/**
 * 分类筛选底部抽屉（对齐全局抽屉视觉规范：标题+柔粉重置按钮，浅粉柔边选中胶囊）
 */
@Composable
fun TypeSheetDialog(
    filters: List<VodFilter>,
    selectedFilters: Map<String, String>,
    showReset: Boolean,
    onSelectFilter: (filterKey: String, value: VodFilterValue) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    MewSheetShell(
        onDismiss = onDismiss,
        variant = MewOverlayVariant.Light,
    ) { focusModifier ->
        Column(
            modifier = focusModifier
                .fillMaxWidth()
                .padding(horizontal = OverlayTokens.Padding, vertical = 8.dp)
                .navigationBarsPadding(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "筛选",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = OverlayTokens.DialogTitle,
                )

                if (showReset) {
                    Surface(
                        onClick = onReset,
                        shape = ContinuousCapsule,
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "重置",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
            ) {
                items(filters, key = { it.key }) { filter ->
                    FilterGroupRow(
                        filter = filter,
                        selectedValue = selectedFilters[filter.key],
                        onSelect = { value -> onSelectFilter(filter.key, value) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FilterGroupRow(
    filter: VodFilter,
    selectedValue: String?,
    onSelect: (VodFilterValue) -> Unit,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(filter.key) {
        val target = selectedValue ?: return@LaunchedEffect
        val index = filter.values.indexOfFirst { it.v == target }
        if (index > 2) {
            listState.scrollToItem((index - 1).coerceAtLeast(0))
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (filter.name.isNotBlank()) {
            Text(
                text = filter.name,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        LazyRow(
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(
                items = filter.values,
                key = { "${filter.key}_${it.v}_${it.n}" },
            ) { item ->
                val isSelected = selectedValue == item.v
                Surface(
                    onClick = { onSelect(item) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        OverlayTokens.SheetItemBg
                    },
                    border = if (isSelected) {
                        BorderStroke(0.8.dp, MewBoxTheme.colors.primaryBorder)
                    } else {
                        null
                    },
                ) {
                    Text(
                        text = item.n,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 13.5.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            OverlayTokens.TextPrimary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

