package com.magnate.compass.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.TagWriteResult
import com.magnate.compass.data.entity.TAG_PALETTE_SIZE
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.MagnateIcons
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.tagColor

/**
 * 标签管理（design-gui.md §10）。
 *
 * 视觉上与场景页刻意区分：`#` 前缀、标签自选色、列表项只有「名称 + 记录数」
 * 而没有坐标摘要——标签没有空间属性，硬塞一个坐标摘要只会让人以为它也有位置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagManageScreen(
    viewModel: TagViewModel,
    onBack: () -> Unit,
) {
    val tags by viewModel.tags.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<TagWithCount?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TagWithCount?>(null) }
    var paletteFor by remember { mutableStateOf<TagWithCount?>(null) }
    var duplicateName by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("标签管理", style = BodyStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { creating = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建标签")
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
            if (tags.isEmpty()) {
                EmptyState(
                    icon = MagnateIcons.Sell,
                    title = "还没有标签",
                    message = "标签用来横向归类记录。可以在保存记录时顺手新建，" +
                        "也可以在这里预先建好。",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "新建标签",
                    onAction = { creating = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(tags, key = { it.tag.id }) { item ->
                        TagRow(
                            item = item,
                            onEdit = { editing = item },
                            onDelete = { deleting = item },
                            onPickColor = { paletteFor = item },
                        )
                        SectionDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }

                Text(
                    text = "提示：删除标签不会删除记录，只会解除该标签与记录的关联。",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "新建标签",
            initial = "",
            onConfirm = { name ->
                viewModel.create(name) { result ->
                    when (result) {
                        is TagWriteResult.Success -> creating = false
                        is TagWriteResult.Duplicate -> duplicateName = name.trim()
                    }
                }
            },
            onDismiss = { creating = false },
        )
    }

    editing?.let { item ->
        NameDialog(
            title = "重命名标签",
            initial = item.tag.name,
            onConfirm = { name ->
                viewModel.rename(item.tag.id, name) { result ->
                    when (result) {
                        is TagWriteResult.Success -> editing = null
                        is TagWriteResult.Duplicate -> duplicateName = name.trim()
                    }
                }
            },
            onDismiss = { editing = null },
        )
    }

    duplicateName?.let { name ->
        AlertDialog(
            onDismissRequest = { duplicateName = null },
            title = { Text("标签名已存在", style = BodyStyle) },
            text = { Text("已经有一个叫「$name」的标签了。", style = BodyStyle) },
            confirmButton = { TextButton(onClick = { duplicateName = null }) { Text("知道了") } },
        )
    }

    paletteFor?.let { item ->
        ColorPaletteDialog(
            current = item.tag.colorIndex,
            onPick = { index ->
                viewModel.setColor(item.tag.id, index)
                paletteFor = null
            },
            onDismiss = { paletteFor = null },
        )
    }

    deleting?.let { item ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除标签「${item.tag.name}」？", style = BodyStyle) },
            // 必须明确告知不删记录：用户看到「删除」与「12 条记录」放在一起，
            // 本能反应是恐慌。一句清晰说明能消除焦虑，成本为零（§10.3）
            text = {
                Text(
                    text = "该标签关联的 ${item.recordCount} 条记录不会被删除，" +
                        "只是不再带有这个标签。",
                    style = BodyStyle,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(item.tag.id)
                        deleting = null
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun TagRow(
    item: TagWithCount,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPickColor: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 色点即入口：点它换色，即时生效
        Box(
            Modifier
                .size(14.dp)
                .background(tagColor(item.tag.colorIndex, isSystemInDarkTheme()), CircleShape)
                .clickable(onClick = onPickColor)
        )
        Spacer(Modifier.width(12.dp))

        Text(
            text = "#${item.tag.name}",
            style = BodyStyle.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "${item.recordCount} 条记录",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))

        IconButton(onClick = onEdit) {
            Icon(
                Icons.Outlined.Edit,
                contentDescription = "重命名标签 ${item.tag.name}",
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = "删除标签 ${item.tag.name}",
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 8 色调色板（§12.2）。
 *
 * 每个色块带上「颜色 3」这样的语义描述——读屏用户看不到颜色，
 * 若只读「按钮」他无从选择。
 */
@Composable
private fun ColorPaletteDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择颜色", style = BodyStyle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(TAG_PALETTE_SIZE / PALETTE_COLUMNS) { rowIndex ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        repeat(PALETTE_COLUMNS) { columnIndex ->
                            val index = rowIndex * PALETTE_COLUMNS + columnIndex
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .background(tagColor(index, dark), CircleShape)
                                    .clickable { onPick(index) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (index == current) {
                                    Text(
                                        text = "✓",
                                        style = BodyStyle,
                                        color = MaterialTheme.colorScheme.surface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = BodyStyle) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("标签名", style = LabelStyle) },
                placeholder = { Text("室外", style = BodyStyle) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) {
                Text("确定")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 调色板一行放 4 个，8 色正好两行。 */
private const val PALETTE_COLUMNS = 4
