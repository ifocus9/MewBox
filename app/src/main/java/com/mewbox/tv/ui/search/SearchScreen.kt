package com.mewbox.tv.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mewbox.tv.ui.components.EmptyState
import com.mewbox.tv.ui.components.VodCard

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onNavigateToDetail: (siteKey: String, vodId: String, vodName: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        // 搜索栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = { viewModel.onKeywordChange(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索影片、演员、剧集...") },
                singleLine = true,
                leadingIcon = {
                    Icon(imageVector = Icons.Default.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (state.keyword.isNotEmpty()) {
                        IconButton(onClick = { viewModel.onKeywordChange("") }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = "清除")
                        }
                    }
                },
                shape = RoundedCornerShape(24.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        keyboard?.hide()
                        viewModel.search()
                    }
                ),
            )
            Spacer(modifier = Modifier.width(8.dp))
            androidx.compose.material3.Button(
                onClick = {
                    keyboard?.hide()
                    viewModel.search()
                },
                shape = RoundedCornerShape(24.dp),
            ) {
                Text("搜索")
            }
        }

        // 正在全网并发搜索进度条
        if (state.isSearching) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // 搜索历史或热门推荐词（仅在未搜索任何词时展示）
        if (state.results.isEmpty() && !state.isSearching && state.keyword.isEmpty()) {
            Column(modifier = Modifier.padding(16.dp)) {
                if (state.history.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("搜索历史", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(imageVector = Icons.Default.DeleteOutline, contentDescription = "清空历史")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.history.forEach { item ->
                            AssistChip(
                                onClick = {
                                    keyboard?.hide()
                                    viewModel.search(item)
                                },
                                label = { Text(item) },
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Text("热门搜索推荐", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                val hotWords = listOf("流浪", "猫", "繁花", "庆余年", "大世界", "周处", "三体", "热播")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    hotWords.forEach { item ->
                        AssistChip(
                            onClick = {
                                keyboard?.hide()
                                viewModel.search(item)
                            },
                            label = { Text(item) },
                        )
                    }
                }
            }
        }

        // 搜索结果区域
        Box(modifier = Modifier.weight(1f)) {
            when {
                state.results.isEmpty() && state.isSearching -> {
                    // 正在搜索但还没出第一批结果
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("正在全网并发检索中...", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                state.results.isEmpty() && !state.isSearching && state.keyword.isNotEmpty() -> {
                    EmptyState(message = "未找到与 \"${state.keyword}\" 相关的结果")
                }
                state.results.isNotEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(state.results, key = { "${it.siteKey}_${it.vodId}" }) { vod ->
                            VodCard(
                                vod = vod,
                                onClick = { onNavigateToDetail(vod.siteKey, vod.vodId, vod.vodName) },
                            )
                        }
                    }
                }
            }
        }
    }
}
