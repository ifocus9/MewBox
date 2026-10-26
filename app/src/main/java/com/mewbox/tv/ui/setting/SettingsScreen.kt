package com.mewbox.tv.ui.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.layout.statusBarsPadding

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }

    // 首次拿到持久化值时回填输入框
    LaunchedEffect(state.savedConfigUrl) { if (input.isEmpty()) input = state.savedConfigUrl }
    LaunchedEffect(state.savedHost) { if (host.isEmpty()) host = state.savedHost }

    LazyColumn(
        modifier = modifier.fillMaxWidth().statusBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    ) {
        item { Text("设置", style = MaterialTheme.typography.headlineMedium) }

        item {
            SectionCard("订阅配置") {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("config.json 地址或 JSON 文本") },
                    maxLines = 4,
                )
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.import(input) }, enabled = !state.importing) { Text("导入") }
                    if (state.importing) {
                        Spacer(Modifier.size(12.dp))
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                state.message?.let {
                    Spacer(Modifier.size(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item {
            SectionCard("本地地址映射") {
                Text(
                    "配置中的 127.0.0.1 / localhost 将被替换为此地址（含播放直链）。" +
                        "模拟器填 10.0.2.2；真机可填局域网 IP，或使用 adb reverse tcp:9988 tcp:9988 后留空。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("目标主机（可带端口，留空不映射）") },
                    singleLine = true,
                )
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { viewModel.saveHost(host) }) { Text("保存映射") }
            }
        }

        item { Text("站点（${state.sites.size}）", style = MaterialTheme.typography.titleMedium) }

        items(state.sites, key = { it.site.key }) { item ->
            SiteRow(item, viewModel)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}

@Composable
private fun SiteRow(item: SiteItem, vm: SettingsViewModel) {
    val site = item.site
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (item.supported) 1f else 0.5f),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setHome(site) }, enabled = item.supported) {
                Icon(
                    if (site.isHome) Icons.Filled.Home else Icons.Outlined.Home,
                    contentDescription = "设为首页源",
                    tint = if (site.isHome) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(site.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (item.supported) site.key else "${site.key} · 暂不支持 (type=${site.type})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            LabeledSwitch("启用", site.enabled, item.supported) { vm.toggleEnabled(site) }
            LabeledSwitch("搜索", site.searchable, item.supported) { vm.toggleSearchable(site) }
            LabeledSwitch("换源", site.changeable, item.supported) { vm.toggleChangeable(site) }
        }
    }
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 6.dp)) {
        Switch(checked = checked, onCheckedChange = { onToggle() }, enabled = enabled)
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
