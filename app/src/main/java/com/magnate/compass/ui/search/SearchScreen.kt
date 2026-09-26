package com.magnate.compass.ui.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.MagnateIcons
import com.magnate.compass.ui.common.Pill
import com.magnate.compass.ui.records.RecordListEntry
import com.magnate.compass.ui.records.RecordListItem
import com.magnate.compass.ui.records.groupRecordsByDay
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle

/**
 * 搜索与筛选（design-gui.md §8）。
 *
 * 与记录列表页的分工：列表页承担「快速浏览某一类」的轻量任务，条件只有单选；
 * 这里承担组合条件的精确查找，关键词与筛选条件之间是 **AND**。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(
    viewModel: SearchViewModel,
    onBack: () -> Unit,
    onOpenRecord: (Long) -> Unit,
) {
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val records by viewModel.records.collectAsStateWithLifecycle()
    val resultCount by viewModel.resultCount.collectAsStateWithLifecycle()
    val activeFilters by viewModel.activeFilters.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val draftResultCount by viewModel.draftResultCount.collectAsStateWithLifecycle()

    val nowMillis = remember(records) { System.currentTimeMillis() }
    val entries = remember(records, nowMillis) { groupRecordsByDay(records, nowMillis) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = viewModel::setKeyword,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索备注、标签或场景", style = BodyStyle) },
                        leadingIcon = {
                            Icon(
                                Icons.Outlined.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                        trailingIcon = {
                            if (keyword.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearKeyword) {
                                    Icon(Icons.Outlined.Clear, contentDescription = "清空输入")
                                }
                            }
                        },
                        singleLine = true,
                        textStyle = BodyStyle,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            ActiveFilterRow(
                filters = activeFilters,
                onOpenSheet = viewModel::openFilterSheet,
                onRemove = viewModel::removeActiveFilter,
            )

            Text(
                text = "找到 $resultCount 条记录",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            when {
                entries.isNotEmpty() -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.forEach { entry ->
                        when (entry) {
                            is RecordListEntry.DayHeader -> stickyHeader(key = entry.key) {
                                Text(
                                    text = entry.label,
                                    style = LabelStyle,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }

                            is RecordListEntry.Item -> item(key = entry.key) {
                                RecordListItem(
                                    item = entry.record,
                                    onClick = { onOpenRecord(entry.record.record.id) },
                                )
                            }
                        }
                    }
                }

                // 两种失败原因要分清：关键词打错了 vs 条件设太死（§8.5）
                keyword.isNotBlank() -> EmptyState(
                    icon = MagnateIcons.SearchOff,
                    title = "没有找到匹配的记录",
                    message = "「$keyword」没有匹配的备注、标签或场景",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "清除关键词",
                    onAction = viewModel::clearKeyword,
                    secondaryActionLabel = "重置筛选",
                    onSecondaryAction = viewModel::resetFilters,
                )

                else -> EmptyState(
                    icon = MagnateIcons.Tune,
                    title = "没有符合条件的记录",
                    message = "筛选条件可能过窄，试试放宽",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "重置筛选",
                    onAction = viewModel::resetFilters,
                )
            }
        }
    }

    draft?.let { current ->
        FilterSheet(
            draft = current,
            scenes = scenes,
            tags = tags,
            resultCount = draftResultCount,
            onDraftChange = viewModel::updateDraft,
            onReset = viewModel::resetDraft,
            onConfirm = viewModel::confirmDraft,
            onDismiss = viewModel::closeFilterSheet,
        )
    }
}

/**
 * 活跃筛选 chip 行（§8.3）。
 *
 * 这解决了筛选面板最大的可用性问题：用户设了五个条件后忘了设过什么，
 * 看到空结果也不知道为什么。每枚 chip 都能单独移除，不必重开面板逐项排查。
 */
@Composable
private fun ActiveFilterRow(
    filters: List<ActiveFilter>,
    onOpenSheet: () -> Unit,
    onRemove: (ActiveFilter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Pill(
            text = if (filters.isEmpty()) "筛选" else "筛选 · ${filters.size}",
            leadingText = "⚙",
            selected = filters.isNotEmpty(),
            onClick = onOpenSheet,
        )
        filters.forEach { filter ->
            Pill(
                text = filter.label,
                trailingIcon = Icons.Outlined.Close,
                onTrailingClick = { onRemove(filter) },
                selected = true,
                onClick = { onRemove(filter) },
            )
        }
    }
}
