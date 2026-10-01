package com.magnate.compass.ui.satellite

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magnate.compass.location.GnssBlock
import com.magnate.compass.location.GnssFormat
import com.magnate.compass.location.GnssSnapshot
import com.magnate.compass.location.SatelliteInfo
import com.magnate.compass.location.SignalTier
import com.magnate.compass.ui.common.Banner
import com.magnate.compass.ui.common.LocalLocationPermissionGate
import com.magnate.compass.ui.common.SectionDivider
import com.magnate.compass.ui.common.SectionTitle
import com.magnate.compass.ui.theme.AxisValueStyle
import com.magnate.compass.ui.theme.BodyStyle
import com.magnate.compass.ui.theme.LabelStyle
import com.magnate.compass.ui.theme.LocalMagnateSemanticColors
import com.magnate.compass.ui.theme.MagnitudeDetailStyle

/**
 * 卫星信号（design-gui.md §18）。
 *
 * 这个页面存在的唯一理由：让「为什么定不上位」当场可判断。
 * 在此之前，用户能拿到的只有一句「定位失败，请到窗边或室外再试」——
 * 而真实原因可能是没权限、可能是总开关关了、也可能是真的被楼挡住了，
 * 三者在应用里长得一模一样。
 *
 * 因此有两件事必须写清楚，否则用户一定会误读：
 * 1. **卫星只在引擎运转时出现**，引擎只在有活跃定位请求时运转（本页会自己持一个）。
 * 2. **可见 ≠ 参与定位**。能收到信号不等于解算时用得上它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SatelliteScreen(
    viewModel: SatelliteViewModel,
    onBack: () -> Unit,
) {
    // RESUMED 而非默认的 STARTED：与主页传感器的生命周期对齐，
    // 保证这份「可见才注册」的纪律在转场动画期间也成立
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val gate = LocalLocationPermissionGate.current
    val hardwareYear = remember { viewModel.hardwareYear() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("卫星信号", style = BodyStyle) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    // 用文字而不是图标：`material-icons-core` 里没有合适的「刷新」，
                    // 而 extended 包被禁用（见 MagnateIcons 的说明）
                    TextButton(onClick = viewModel::retry) { Text("重试") }
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
            GnssFormat.formatBanner(snapshot)?.let { banner ->
                Banner(
                    message = banner,
                    // 权限不足时才给按钮。服务关闭与硬件不可用都得去系统设置或换设备，
                    // 在这个页面上放一个按了没反应的按钮比不放更糟
                    onAction = if (snapshot.blocked == GnssBlock.PERMISSION) {
                        {
                            // 用 requestFineLocation 而不是 request：能走到这个横幅，
                            // 说明权限判定是「已授予」（只有粗略），request 会同步回调
                            // onGranted 而不弹任何窗，按钮就成了摆设。
                            // 授权后必须 retry——流的权限检查只在收集开始时发生一次。
                            gate.requestFineLocation(onGranted = viewModel::retry)
                        }
                    } else {
                        null
                    },
                )
            }

            SummaryCard(snapshot = snapshot, hardwareYear = hardwareYear)

            Spacer(Modifier.height(8.dp))
            SectionDivider()

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionTitle("卫星 · ${snapshot.visibleCount} 颗")
                Spacer(Modifier.weight(1f))
                Text(
                    text = GnssFormat.formatEngineState(snapshot),
                    style = LabelStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val satellites = remember(snapshot) { viewModel.sortedForDisplay(snapshot) }

            if (satellites.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (snapshot.blocked != null) {
                            "暂时没有卫星数据"
                        } else {
                            "正在搜索卫星…\n室内通常搜不到，到窗边或室外会快很多。"
                        },
                        style = BodyStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(satellites, key = { it.key }) { satellite ->
                        SatelliteRow(satellite)
                    }

                    item { FooterNote() }
                }
            }
        }
    }
}

/** 汇总卡：可见与参与、引擎状态、首次定位耗时、GNSS 硬件年份。 */
@Composable
private fun SummaryCard(snapshot: GnssSnapshot, hardwareYear: Int?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            text = GnssFormat.formatSummary(snapshot),
            style = MagnitudeDetailStyle,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(Modifier.height(4.dp))
        Text(
            text = GnssFormat.formatEngineState(snapshot),
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GnssFormat.formatFirstFix(snapshot.firstFixElapsedMs)?.let {
            Spacer(Modifier.height(2.dp))
            Text(text = it, style = LabelStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // 硬件年份为 null 时整行不渲染——很多设备根本不报这个值，
        // 显示「GNSS 硬件 0」比不显示更让人困惑
        hardwareYear?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "GNSS 硬件 $it 年",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 一颗卫星一行。
 *
 * 星座字母与 C/N0 数字**始终保留**，颜色只是辅助——只靠颜色区分强度，
 * 色觉障碍用户与黑白截图都读不出任何信息（design-gui.md §15）。
 */
@Composable
private fun SatelliteRow(satellite: SatelliteInfo) {
    val tier = GnssFormat.cn0Tier(satellite.cn0DbHz)
    val accent = tierColor(tier)

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 星座标记：字母是主，颜色是辅
        Text(
            text = satellite.constellation.shortName,
            style = MagnitudeDetailStyle,
            color = accent,
            modifier = Modifier.width(20.dp),
        )
        Text(
            text = satellite.svid.toString(),
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(34.dp),
        )

        // 信噪比条：固定 0–50 dB-Hz 量程，不按当前最大值自适应。
        // 自绘而不是 LinearProgressIndicator，与 MagneticBar 保持同一种画法
        Column(Modifier.weight(1f)) {
            SignalBar(
                fraction = GnssFormat.cn0BarFraction(satellite.cn0DbHz),
                color = accent,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${satellite.constellation.displayName} · " +
                    "方位 ${GnssFormat.formatAngle(satellite.azimuthDeg)} · " +
                    "仰角 ${GnssFormat.formatAngle(satellite.elevationDeg)}",
                style = LabelStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(8.dp))
        Text(
            text = GnssFormat.formatCn0(satellite.cn0DbHz),
            style = AxisValueStyle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(30.dp),
        )

        // 「参与」是这一页最容易被误读的一栏，所以它用文字而不是图标——
        // 一个勾或一个点，用户不会知道那代表什么
        Text(
            text = if (satellite.usedInFix) "参与" else "—",
            style = LabelStyle,
            color = if (satellite.usedInFix) {
                LocalMagnateSemanticColors.current.success
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(32.dp),
        )
    }
}

/**
 * 页脚。写清两件用户必然会误读的事。
 *
 * 不放在顶部是因为它是**说明**而不是**状态**：用户先看到数字，
 * 有疑问再往下读；把两段解释摆在数字前面，只会让人跳过。
 */
@Composable
private fun FooterNote() {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "卫星只在 GNSS 引擎运转时出现。本页会自行发起一次定位请求让引擎转起来，" +
                "离开这一页后立刻停止。",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "「可见」是收到了信号，「参与」是解算时真的用上了它。" +
                "可见很多而参与为零，通常意味着还在搜星，或者信号太弱不足以解算。",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "信噪比条的量程固定为 0–50 dB-Hz。40 以上是开阔天空的好值，" +
                "30 左右才足以参与解算。",
            style = LabelStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 信噪比条。
 *
 * `fraction` 已经是固定量程上的占比（[GnssFormat.cn0BarFraction]），
 * 这里不再做任何缩放——量程只有一处定义，改阈值时不会漏掉画图这一边。
 */
@Composable
private fun SignalBar(fraction: Float, color: Color, trackColor: Color) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
    ) {
        val radius = size.height / 2f
        drawRoundRect(color = trackColor, cornerRadius = CornerRadius(radius, radius))
        if (fraction > 0f) {
            drawRoundRect(
                color = color,
                size = Size(size.width * fraction, size.height),
                cornerRadius = CornerRadius(radius, radius),
            )
        }
    }
}

/** 分档 → 颜色。阈值全在 [GnssFormat] 里，这里只做映射，不重复写数字。 */
@Composable
private fun tierColor(tier: SignalTier): Color {
    val semantic = LocalMagnateSemanticColors.current
    return when (tier) {
        SignalTier.STRONG -> semantic.success
        SignalTier.GOOD -> MaterialTheme.colorScheme.primary
        SignalTier.WEAK -> semantic.warning
        SignalTier.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}
