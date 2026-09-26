package com.magnate.compass.ui.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.magnate.compass.data.SceneWithCount
import com.magnate.compass.data.SceneWriteResult
import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.ui.common.EmptyState
import com.magnate.compass.ui.common.Pill
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.util.GeoFormat

/**
 * 场景列表（design-gui.md §9.1）。
 *
 * 无坐标的场景用 `⚠` + warning 色，但**不排到末尾**：文档权衡过「待完善项不该占据视觉首位」
 * 与「排序可预测」，最终选了可预测性，只用颜色提醒。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneListScreen(
    viewModel: SceneViewModel,
    onBack: () -> Unit,
    onOpenScene: (Long) -> Unit,
    onOpenRecord: (Long) -> Unit,
) {
    val scenes by viewModel.scenes.collectAsStateWithLifecycle()
    val activeSceneId by viewModel.activeSceneId.collectAsStateWithLifecycle()
    val recentlyLocated by viewModel.recentlyLocated.collectAsStateWithLifecycle()

    var showCreateSheet by remember { mutableStateOf(false) }
    var duplicateSceneId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("场景管理", style = BodyStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = "新建场景")
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
            if (scenes.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.Place,
                    title = "还没有场景",
                    message = "场景是一组测量的空间容器。室内没有 GPS 时，" +
                        "归属场景的记录会继承场景的坐标。",
                    modifier = Modifier.fillMaxSize(),
                    actionLabel = "新建场景",
                    onAction = { showCreateSheet = true },
                )
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(scenes, key = { it.scene.id }) { item ->
                        SceneCard(
                            item = item,
                            isActive = item.scene.id == activeSceneId,
                            onClick = { onOpenScene(item.scene.id) },
                        )
                    }
                }

                Text(
                    text = "提示：场景坐标为「固定」模式时，场景内所有记录共用该坐标；" +
                        "修改场景坐标会同步改变这些记录的坐标。",
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
    }

    if (showCreateSheet) {
        SceneEditSheet(
            title = "新建场景",
            initialName = "",
            initialNote = null,
            initialMode = CoordMode.FIXED,
            initialPoint = null,
            autoAcquireOnOpen = true,
            recentlyLocated = recentlyLocated,
            onAcquire = viewModel::acquireLocation,
            onOpenRecord = onOpenRecord,
            onSubmit = { draft ->
                // `when (val result = ...)` 的 result 只在 when 内部可见，因此先绑出来
                val result = viewModel.createScene(draft)
                when (result) {
                    is SceneWriteResult.Success -> {
                        showCreateSheet = false
                        // 建完就进去——用户建场景几乎总是为了马上在里面测量
                        viewModel.enterScene(result.sceneId)
                        onOpenScene(result.sceneId)
                    }

                    is SceneWriteResult.DuplicateName -> duplicateSceneId = result.existingSceneId

                    SceneWriteResult.NotFound -> Unit
                }
                // 面板要拿返回值决定是收起还是提示重名，不能在这里吞掉
                result
            },
            onDismiss = { showCreateSheet = false },
        )
    }

    duplicateSceneId?.let { existingId ->
        DuplicateNameDialog(
            onViewScene = {
                duplicateSceneId = null
                showCreateSheet = false
                onOpenScene(existingId)
            },
            onDismiss = { duplicateSceneId = null },
        )
    }
}

@Composable
private fun SceneCard(
    item: SceneWithCount,
    isActive: Boolean,
    onClick: () -> Unit,
) {
    val scene = item.scene
    val semanticColors = LocalMagnateSemanticColors.current
    val hasCoordinate = scene.hasCoordinate

    val borderColor = if (isActive) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (hasCoordinate) Icons.Outlined.Place else Icons.Outlined.Warning,
            contentDescription = null,
            tint = if (hasCoordinate) MaterialTheme.colorScheme.primary else semanticColors.warning,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scene.name,
                    style = BodyStyle.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Pill(text = "✓ 当前", selected = true, onClick = onClick)
                }
            }

            Text(
                text = if (hasCoordinate) {
                    GeoFormat.formatCoordinatesShort(scene.latitude!!, scene.longitude!!) +
                        " · ${GeoFormat.formatAccuracy(scene.locationAccuracy ?: Float.MAX_VALUE)}"
                } else {
                    "未设置坐标"
                },
                style = LabelStyle,
                color = if (hasCoordinate) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    semanticColors.warning
                },
            )

            Text(
                text = "${if (scene.coordMode == CoordMode.FIXED) "固定坐标" else "跟随定位"} · " +
                    "${item.recordCount} 条记录",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}


