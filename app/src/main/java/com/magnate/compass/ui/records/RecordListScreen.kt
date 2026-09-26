package com.magnate.compass.ui.records

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SortField
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.MagnateIcons
import com.magnate.compass.ui.common.Pill
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.tagColor
import kotlinx.coroutines.launch

/**
 * 记录列表（design-gui.md §6）。
 *
 * 顶部快捷筛选是**单选**的：列表页承担「快速浏览某一类」的轻量任务，
 * 多选与组合条件留给搜索页（§6.5）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun RecordListScreen(
    viewModel: RecordsViewModel,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenRecord: (Long) -> Unit,
    onOpenScenes: () -> Unit,
    onOpenTags: () -> Unit,
    onGoMeasure: () -> Unit,
) {
    val records by viewModel.records.collectAsStateWithLifecycle()
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val tags by viewModel.tags.collectAsStateWithLifecycle()
    val quickFilter by viewModel.quickFilter.collectAsStateWithLifecycle()
    val sortBy by viewModel.sortBy.collectAsStateWithLifecycle()
    val sortAsc by viewModel.sortAsc.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showMenu by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }

    // 「今天」在列表可见期间应当稳定；每次重组都取当前时间会让分组头闪烁
    val nowMillis = remember(records) { System.currentTimeMillis() }
    val entries = remember(records, nowMillis) { groupRecordsByDay(records, nowMillis) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("测试记录", style = BodyStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = "搜索与筛选",
                        )
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "更多操作",
                            )
                        }
                        OverflowMenu(
                            expanded = showMenu,
                            onDismiss = { showMenu = false },
                            sortBy = sortBy,
                            sortAsc = sortAsc,
                            onSort = viewModel::setSort,
                            onManageScenes = {
                                showMenu = false
                                onOpenScenes()
                            },
                            onManageTags = {
                                showMenu = false
                                onOpenTags()
                            },
                            onClearAll = {
                                showMenu = false
                                showClearDialog = true
                            },
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            QuickFilterRow(
                scenes = scenes,
                tags = tags,
                selected = quickFilter,
                onToggle = viewModel::toggleQuickFilter,
                onLongPressScene = onOpenScenes,
                onLongPressTag = onOpenTags,
            )

            when {
                // 「还没有记录」与「筛选无结果」是两种不同的空状态，文案必须区分（§6.7）
                totalCount == 0 -> EmptyState(
                    icon = Icons.AutoMirrored.Outlined.List,
                    title = "还没有测试记录",
                    message = "回到主页，点击「保存当前读数」即可记录第一次测量",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "去测量",
                    onAction = onGoMeasure,
                )

                entries.isEmpty() -> EmptyState(
                    icon = MagnateIcons.SearchOff,
                    title = "没有符合条件的记录",
                    message = "试试放宽筛选条件",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "清除筛选",
                    onAction = viewModel::clearFilters,
                )

                else -> LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    entries.forEach { entry ->
                        when (entry) {
                            is RecordListEntry.DayHeader -> stickyHeader(key = entry.key) {
                                DayHeader(entry.label)
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
            }
        }
    }

    if (showClearDialog) {
        ClearAllDialog(
            count = totalCount,
            onConfirm = {
                showClearDialog = false
                viewModel.clearAllRecords {
                    scope.launch {
                        snackbarHostState.showSnackbar("已清空所有记录")
                    }
                }
            },
            onDismiss = { showClearDialog = false },
        )
    }
}

/** 日期分组头。sticky 让用户在长列表里始终知道自己在看哪一天。 */
@Composable
private fun DayHeader(label: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = LabelStyle.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    }
}

/**
 * 快捷筛选行：场景与标签并列。
 *
 * 场景用 `📍` 前缀 + `primary` 色调，标签用 `#` 前缀 + 标签色。
 * 前缀与配色**共同**强化两者的概念区分——用户不该觉得「场景」和「标签」是同一类东西（§6.5）。
 */
@Composable
private fun QuickFilterRow(
    scenes: List<SceneWithCount>,
    tags: List<TagWithCount>,
    selected: QuickFilter,
    onToggle: (QuickFilter) -> Unit,
    onLongPressScene: () -> Unit,
    onLongPressTag: () -> Unit,
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
            text = "全部",
            selected = selected == QuickFilter.All,
            onClick = { onToggle(QuickFilter.All) },
        )

        scenes.forEach { item ->
            val filter = QuickFilter.Scene(item.scene.id)
            Pill(
                text = item.scene.name,
                leadingText = "📍",
                trailingText = item.recordCount.toString(),
                selected = selected == filter,
                onClick = { onToggle(filter) },
                onLongClick = onLongPressScene,
            )
        }

        tags.forEach { item ->
            val filter = QuickFilter.Tag(item.tag.id)
            Pill(
                text = item.tag.name,
                leadingText = "#",
                trailingText = item.recordCount.toString(),
                selected = selected == filter,
                // 标签 chip 用标签自选色，与场景 chip 的 primary 色调区分开
                accent = tagColor(item.tag.colorIndex, isSystemInDarkTheme()),
                onClick = { onToggle(filter) },
                onLongClick = onLongPressTag,
            )
        }
    }
}

/**
 * 「⋮」菜单。
 *
 * 排序在 design-gui.md §6.6 里写作「子菜单」，但 Material3 的 `DropdownMenu`
 * 不支持嵌套子菜单。这里改为两个平铺项、各自的箭头指示当前升降序——
 * 信息量完全相同，且少一层点击。
 */
@Composable
private fun OverflowMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    sortBy: SortField,
    sortAsc: Boolean,
    onSort: (SortField) -> Unit,
    onManageScenes: () -> Unit,
    onManageTags: () -> Unit,
    onClearAll: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        SortMenuItem("按时间", SortField.TIME, sortBy, sortAsc) { onSort(SortField.TIME) }
        SortMenuItem("按磁场强度", SortField.MAGNITUDE, sortBy, sortAsc) { onSort(SortField.MAGNITUDE) }

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("管理场景", style = BodyStyle) },
            leadingIcon = { Icon(Icons.Outlined.Place, contentDescription = null) },
            onClick = onManageScenes,
        )
        DropdownMenuItem(
            text = { Text("管理标签", style = BodyStyle) },
            leadingIcon = { Icon(MagnateIcons.Sell, contentDescription = null) },
            onClick = onManageTags,
        )

        HorizontalDivider()

        DropdownMenuItem(
            text = { Text("清空所有记录", style = BodyStyle, color = MaterialTheme.colorScheme.error) },
            leadingIcon = {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onClearAll,
        )
    }
}

@Composable
private fun SortMenuItem(
    label: String,
    field: SortField,
    currentField: SortField,
    ascending: Boolean,
    onClick: () -> Unit,
) {
    val active = field == currentField
    val arrow = if (active && ascending) "↑" else "↓"
    DropdownMenuItem(
        text = {
            Text(
                text = if (active) "$label  $arrow" else label,
                style = BodyStyle,
                color = if (active) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        },
        onClick = onClick,
    )
}

/**
 * 清空所有记录。
 *
 * **不做撤销**——太危险。改用「输入『删除』二字」把误触概率降到接近零（§6.6）。
 */
@Composable
private fun ClearAllDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val confirmed = text.trim() == "删除"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("清空所有记录？", style = BodyStyle) },
        text = {
            Column {
                Text(
                    text = "将删除全部 $count 条记录，包括它们的备注与标签关联。" +
                        "场景与标签本身会保留。\n\n此操作无法撤销。请输入「删除」以确认。",
                    style = BodyStyle,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmed) {
                Text("清空", color = if (confirmed) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
