# Magnate 设计文档

> 一个基于手机内置传感器的安卓应用：实时测量**方位角**与**磁场大小**，以**场景**为单位组织采样数据，记录坐标、保存测量值、打标签、检索回溯。

- 版本：v1.2（设计稿）
- 日期：2026-09-24
- 需求来源：`req.txt`
- 配套文档：界面设计见 [`design-gui.md`](./design-gui.md)

---

## 1. 需求分析

### 1.1 原始需求

> 做一个安卓 app 程序，可以利用手机的传感器，测量方位角和磁场大小。

> 增加获取取样点坐标的功能；增加保存当前测试记录的功能，要保存时间和测量值；可以对测试记录添加 tag 标签，方便后期查找；并增加搜索功能。

> 增加一个场景设置，可以为场景增加坐标信息，比如户外获取坐标，然后进入这个场景进行磁场的测量，数值就都属于这个场景的坐标。

### 1.2 需求拆解

| 编号 | 需求 | 说明 |
| --- | --- | --- |
| R1 | 方位角测量 | 输出 0°–360° 的方位角 |
| R2 | 磁场大小测量 | 输出磁场矢量三分量 (X/Y/Z) 与总强度，单位 μT |
| R3 | 实时刷新 | 传感器数据连续刷新，≥ 20 Hz 显示刷新 |
| R4 | 可视化 | 罗盘表盘 + 数字读数 |
| R5 | 精度提示 | 磁力计需校准或受干扰时给出提示 |
| R6 | 设备兼容 | 无磁力计/陀螺仪时明确降级提示，不崩溃 |
| R7 | 采样点坐标 | 记录每条数据的地理位置（经纬度 + 海拔 + 定位精度） |
| R8 | 保存测试记录 | 一键保存当前读数快照，含时间与全部测量值 |
| R9 | 标签 | 记录可打多个 tag，支持自定义、增删、着色 |
| R10 | 搜索 | 关键词搜索 + 时间/磁场/坐标/标签多条件筛选 |
| **R11** | **场景** | **场景可持有坐标；进入场景后的所有测量自动归属该场景坐标** |

### 1.3 场景需求的解读

R11 描述的是一个**典型的实地工作流**：

```
在户外（GPS 良好）
    ↓  获取并保存坐标
创建场景「阳光大厦 B2层」
    ↓  携带手机进入建筑
    ↓  室内 GPS 完全失效
逐点测量磁场
    ↓
所有记录自动标注为「阳光大厦 B2层」的坐标
```

**这解决了 v1.1 遗留的核心矛盾**：室内采样最有价值，但室内恰恰拿不到 GPS。场景机制把「在每个测量点都要有定位」这个不可能的要求，换成了「每个场景只需要一次定位」——一个在现实中完全可以满足的要求。

**场景不是标签的替代品**，两者正交：

| | 场景 | 标签 |
| --- | --- | --- |
| 本质 | **空间容器**，持有坐标 | 任意分类维度，无坐标 |
| 数量级 | 少（几十个） | 可多（几十到上百） |
| 同时归属 | **至多一个**（当前场景） | 可多个 |
| 主要用途 | 数据归属与空间聚合 | 横向检索与标注 |

### 1.4 非目标（v1.0–v1.2 不做，列入 Roadmap）

- 数据导出（CSV / GeoJSON / KML）
- 地图视图与磁场热力图
- 硬磁/软磁椭球拟合校准
- 云端同步、多设备协作
- 场景嵌套（场景下的子区域）
- 后台常驻采集

> **定位重申**：本应用是**手动点按采样**工具，不是连续记录仪。这一点决定了功耗设计（§9）、权限策略（前台定位即可）与 UI 形态（保存按钮是主操作）。

---

## 2. 传感器方案选型

### 2.1 方案对比

| 方案 | 使用传感器 | 优点 | 缺点 | 采用 |
| --- | --- | --- | --- | --- |
| A. 旋转矢量 | `TYPE_ROTATION_VECTOR` | 系统融合加速度计+磁力计+陀螺仪，稳定、无抖动、低延迟 | 依赖陀螺仪 | **主方案** |
| B. 地磁旋转矢量 | `TYPE_GEOMAGNETIC_ROTATION_VECTOR` | 不依赖陀螺仪，功耗极低 | 响应慢，动态场景滞后明显 | 备选（无陀螺仪设备） |
| C. 加速度计 + 磁力计 | `TYPE_ACCELEROMETER` + `TYPE_MAGNETIC_FIELD` | 兼容性最好 | 需自算旋转矩阵，抖动大 | **兜底方案** |
| D. 已废弃方向传感器 | `TYPE_ORIENTATION` | —— | 官方已废弃，精度差 | 不使用 |

**降级链**：A → B → C → 报错提示。

### 2.2 磁场数据

磁场**始终**直接读取 `TYPE_MAGNETIC_FIELD` 原始值。旋转矢量经过融合与归一化，丢失了真实场强量级，不能用于强度测量。

---

## 3. 定位方案选型

### 3.1 方案对比

| 方案 | 依赖 | 精度/功耗 | 国内可用性 | 采用 |
| --- | --- | --- | --- | --- |
| A. `FusedLocationProviderClient` | Google Play Services | 最优，最省电 | ❌ **大量国产设备无 GMS**，调用直接抛异常 | **否** |
| B. `LocationManager.getCurrentLocation()` | 无（系统 API 30+） | 良好 | ✅ | **主方案** |
| C. `LocationManager.requestSingleUpdate()` | 无（API 1+，已废弃） | 良好 | ✅ | API 24–29 兜底 |
| D. 持续 `requestLocationUpdates` | 无 | 可控 | ✅ | **缓存维护**（§5.7） |

**决策：不引入 Google Play Services**，使用系统 `LocationManager`。国产 ROM 普遍不带 GMS，依赖 Fused API 会让应用在主力机型上直接失去定位能力。

### 3.2 定位精度必须一并保存

`Location.getAccuracy()` 返回定位的 95% 置信半径（米）。**±5m 和 ±2000m 的坐标价值完全不同。**

只存经纬度而不存精度的设计是有害的——它让用户误以为所有坐标同等可信。`locationAccuracy` 是记录表与场景表的**必存字段**，UI 必须显示精度等级。

### 3.3 三级降级：把"必须有定位"降为"每个场景一次定位"

这是 R11 带来的根本简化。有效坐标的来源有三层，按优先级：

| 优先级 | 来源 | 何时适用 |
| --- | --- | --- |
| 1 | 记录自身实测定位 | 无场景，或场景为「跟随」模式且当前拿到定位 |
| 2 | **所属场景的坐标** | **场景「固定」模式，或跟随模式下未拿到定位** |
| 3 | 无坐标 | 上述皆无 |

第 2 层是场景机制的价值所在。它把定位需求从**每点一次**降到**每场景一次**——一个在室内场景下从"不可能"变成"完全可行"的转变。

### 3.4 权限与降级

定位权限被拒绝时**不阻塞任何操作**。场景可无坐标创建，记录可无坐标保存，事后均可补录。

---

## 4. 技术选型

| 项目 | 选择 | 理由 |
| --- | --- | --- |
| 语言 | Kotlin | 官方首选，协程/Flow 与传感器流天然契合 |
| UI | Jetpack Compose | 声明式状态驱动，罗盘用 `Canvas` 绘制简洁 |
| 导航 | Navigation Compose | 多页应用 |
| 持久化 | Room | 编译期校验 SQL，支持外键级联与 `@Relation` |
| 轻量配置 | DataStore (Preferences) | 存「当前激活场景 ID」等少量状态 |
| 注解处理 | KSP | Room 官方推荐，比 KAPT 快 |
| 架构 | MVVM + Repository | 传感器/数据逻辑与 UI 解耦，便于单测 |
| 数据流 | `CallbackFlow` → `StateFlow` / `Flow` | 传感器回调桥接协程流，自动生命周期感知 |
| 异步 | Kotlin Coroutines | `repeatOnLifecycle` 保证后台自动注销 |
| 依赖注入 | 手动（`AppContainer`） | 依赖数量少，Hilt 的构建期开销不值 |
| 最低版本 | minSdk 24 (Android 7.0) | **定位需处理 API 30 前后两套路径** |
| 目标版本 | targetSdk 35 | 适配最新平台要求 |
| 构建 | Gradle 8.x + Version Catalog | 依赖集中管理 |

**依赖原则**：只使用 AndroidX / Jetpack 官方组件，**不引入非 AndroidX 的第三方库**。地图、图表、动画库一律自绘或不做。

---

## 5. 架构设计

### 5.1 分层

```
┌──────────────────────────────────────────────────────────────┐
│  UI 层 (Compose)                                              │
│  Compass / RecordList / RecordDetail / Search / Tag / Scene   │
└────────┬─────────────────────────┬───────────────────────────┘
         │ StateFlow               │ StateFlow
┌────────▼──────────┐   ┌──────────▼────────────────────────┐
│ CompassViewModel  │   │ RecordsViewModel / SceneViewModel  │
│ 传感器状态、平滑    │   │ 列表、搜索、标签与场景 CRUD         │
└────────┬──────────┘   └──────────┬────────────────────────┘
         │                         │
┌────────▼──────────┐   ┌──────────▼────────────────────────┐
│ SensorRepository  │   │ RecordRepository                   │
│ 注册/注销、降级     │   │ 保存、查询、标签、场景（唯一写入入口）│
└────────┬──────────┘   └──────────┬────────────────────────┘
         │                         │
┌────────▼──────────┐   ┌──────────▼────────────────────────┐
│ LocationProvider  │   │ Room (RecordDao/TagDao/SceneDao)   │
│ 缓存 + 按需定位     │   │ + DataStore (activeSceneId)        │
└────────┬──────────┘   └──────────┬────────────────────────┘
         │                         │
┌────────▼─────────────────────────▼────────────────────────┐
│ SensorManager / LocationManager / SQLite / DataStore        │
└─────────────────────────────────────────────────────────────┘
```

`RecordRepository` 是唯一写入入口，负责把**传感器快照 + 坐标解析 + 场景归属 + 标签**合成一条完整记录。**UI 不得自行拼装记录对象，也不得自行解析坐标来源。**

### 5.2 传感器数据模型

```kotlin
data class RawSensorData(
    val azimuthRad: Float, val pitchRad: Float, val rollRad: Float,
    val magX: Float, val magY: Float, val magZ: Float,
    val accuracy: Int, val timestampNanos: Long,
)

data class CompassUiState(
    val azimuthDeg: Float = 0f,
    val headingName: String = "北",
    val magnitudeUt: Float = 0f,
    val magX: Float = 0f, val magY: Float = 0f, val magZ: Float = 0f,
    val pitchDeg: Float = 0f, val rollDeg: Float = 0f,
    val accuracy: AccuracyLevel = AccuracyLevel.UNKNOWN,
    val source: SensorSource = SensorSource.ROTATION_VECTOR,
    val trueNorth: Boolean = false,
    val declination: Float? = null,
    val activeScene: SceneEntity? = null,      // 当前场景（R11）
    val locationPreview: EffectiveLocation = EffectiveLocation.None,  // 实时预览
    val error: String? = null,
)

enum class AccuracyLevel { HIGH, MEDIUM, LOW, UNRELIABLE, UNKNOWN }
enum class SensorSource { ROTATION_VECTOR, GEOMAGNETIC_ROTATION_VECTOR, ACCEL_MAG }
```

### 5.3 坐标模型

```kotlin
data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitude: Double?,
    val accuracyMeters: Float,   // 必填。缺失精度的坐标不可信
    val provider: String,        // gps / network / passive
    val locatedAt: Long,
)

/** 有效坐标：解析后的坐标及其来源。来源必须可追溯，绝不静默混同 */
sealed interface EffectiveLocation {
    /** 本机在测量时刻实测 */
    data class Measured(val point: GeoPoint) : EffectiveLocation

    /** 继承自所属场景 */
    data class FromScene(val sceneId: Long, val sceneName: String, val point: GeoPoint)
        : EffectiveLocation

    /** 该记录关联了场景，但场景自身也没有坐标 */
    data class SceneWithoutCoordinate(val sceneId: Long, val sceneName: String)
        : EffectiveLocation

    data object None : EffectiveLocation
}
```

> **`EffectiveLocation` 存在的理由**：一个坐标是"我在这个点实测的"还是"我从场景继承的"，是**语义上完全不同**的两件事。前者说明测量点位于 ±8m 内，后者说明测量点位于该场景范围内的某处——可能相隔几十米。
>
> 把它们都压平成一个 `lat/lng` 字段，用户就再也无法区分。而地图视图、热力图、数据导出都依赖这个区分。**来源信息一旦丢失就无法重建**，所以从模型层就要保住它。

### 5.4 持久化实体（Room）

#### 场景

```kotlin
@Entity(tableName = "scenes", indices = [Index(value = ["name"], unique = true)])
data class SceneEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,                  // 唯一

    // —— 场景坐标（可空：允许先建场景后取坐标）——
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val locationAccuracy: Float?,
    val locationProvider: String?,
    val locatedAt: Long?,

    /** 坐标模式，见 §5.5 */
    val coordMode: CoordMode = CoordMode.FIXED,

    val note: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

enum class CoordMode {
    /** 固定：场景内所有记录共用场景坐标。保存时不做定位，瞬时完成 */
    FIXED,
    /** 跟随：每条记录尝试定位，成功用实测值，失败回退场景坐标 */
    FOLLOW,
}
```

#### 记录

```kotlin
@Entity(
    tableName = "records",
    indices = [Index("timestamp"), Index("magnitude"), Index("createdAt"), Index("sceneId")],
    foreignKeys = [
        ForeignKey(
            entity = SceneEntity::class,
            parentColumns = ["id"], childColumns = ["sceneId"],
            onDelete = ForeignKey.SET_NULL,      // 关键：删场景不删记录
        ),
    ],
)
data class RecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    // —— 时间 ——
    val timestamp: Long,              // 测量时刻（epoch millis，UTC 存储，本地时区显示）
    val createdAt: Long,              // 入库时刻

    // —— 测量值（不可变）——
    val azimuthDeg: Float,
    val pitchDeg: Float, val rollDeg: Float,
    val magX: Float, val magY: Float, val magZ: Float,
    val magnitude: Float,             // 冗余存储，便于 SQL 排序/筛选
    val sensorAccuracy: Int,
    val isTrueNorth: Boolean,
    val declination: Float?,

    // —— 场景归属（R11）——
    val sceneId: Long?,

    // —— 记录自身的实测坐标（可空）——
    // FIXED 模式下恒为 null（坐标从场景解析）
    // FOLLOW 模式或无场景时，定位成功则写入
    val latitude: Double?,
    val longitude: Double?,
    val altitude: Double?,
    val locationAccuracy: Float?,
    val locationProvider: String?,
    val locatedAt: Long?,

    // —— 用户标注 ——
    val note: String?,
)
```

#### 标签

```kotlin
@Entity(tableName = "tags", indices = [Index(value = ["name"], unique = true)])
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val colorIndex: Int = 0,          // 0–7 调色板索引，不存 hex，便于主题适配
    val createdAt: Long,
)

@Entity(
    tableName = "record_tags",
    primaryKeys = ["recordId", "tagId"],
    indices = [Index("tagId")],
    foreignKeys = [
        ForeignKey(RecordEntity::class, ["id"], ["recordId"], onDelete = CASCADE),
        ForeignKey(TagEntity::class, ["id"], ["tagId"], onDelete = CASCADE),
    ],
)
data class RecordTagCrossRef(val recordId: Long, val tagId: Long)
```

**设计说明**：

- **`records.sceneId` 用 `SET_NULL` 而非 `CASCADE`**。记录是用户的核心资产，绝不能因为删除一个场景就丢失。删场景后记录保留，坐标回退到记录自身实测值（若有），否则变为无坐标；
- **`tags` 用 `CASCADE`**。标签关联是纯附属数据，标签没了关联自然失效；
- **`magnitude` 冗余存储**：可由 xyz 派生，但 SQL 排序与范围筛选需要真实列。测量值不可变，不存在不一致风险；
- **测量值不可变**：创建后不提供编辑接口，只允许改备注、标签、补录坐标。这保证数据可信度，也省掉了并发控制与审计日志；
- **`timestamp` 与 `createdAt` 分离**：补录坐标时只更新 `locatedAt`，不污染测量时刻。

### 5.5 场景坐标模式

两种模式对应两种真实的采样方式：

| 模式 | 适用场景 | 保存时行为 | 坐标语义 |
| --- | --- | --- | --- |
| **`FIXED`（固定）** | 室内定点、建筑内逐层测量、设备附近测试 | **不尝试定位，瞬时保存** | 场景坐标（**引用**：场景坐标变更会同步影响所有记录） |
| **`FOLLOW`（跟随）** | 户外移动测绘、沿路径采样 | 尝试定位，最长等 3s | 实测坐标（**快照**：不随场景变更） |

**默认 `FIXED`**，理由有三：

1. 它精确对应用户描述的工作流（户外取坐标 → 室内测量）；
2. 保存永不等待定位，**保存操作始终在 100ms 内完成**，体验最干脆；
3. 同一场景内所有记录坐标完全一致，在热力图/地图上表现为一个干净的点，而非一团无意义的抖动。

**`FIXED` 模式用引用语义**——记录不存自己的坐标，坐标在读取时从场景解析。因此**修改场景坐标会同步改变该场景下所有历史记录的坐标**。

这是一个需要谨慎对待的行为，但也正是用户要的："这些测量值都属于这个场景的坐标"。配套约束：

- 场景坐标变更前弹出确认，明确告知受影响的记录数：「该场景下的 12 条记录坐标将同步更新」；
- 变更后提供 5 秒撤销（恢复原坐标）；
- 变更场景坐标会记录 `updatedAt`，详情页显示「场景坐标更新于 …」，让用户知道底层坐标变过。

**`FOLLOW` 模式用快照语义**——记录一旦写入实测坐标就固定不变，后续修改场景坐标不影响它。

### 5.6 有效坐标解析

```kotlin
/**
 * 纯函数，无副作用，可 JVM 单测。
 * 这是全应用坐标语义的唯一权威实现——UI、导出、未来地图都必须调用它。
 */
fun resolveLocation(record: RecordEntity, scene: SceneEntity?): EffectiveLocation {
    // 1. 记录自身实测坐标（FOLLOW 模式或无场景时的定位结果）
    if (record.latitude != null && record.longitude != null) {
        return EffectiveLocation.Measured(
            GeoPoint(
                latitude = record.latitude,
                longitude = record.longitude,
                altitude = record.altitude,
                accuracyMeters = record.locationAccuracy ?: Float.MAX_VALUE,  // 缺失视为极差
                provider = record.locationProvider ?: "unknown",
                locatedAt = record.locatedAt ?: record.timestamp,
            )
        )
    }

    // 2. 回退到场景坐标
    if (scene != null) {
        if (scene.latitude != null && scene.longitude != null) {
            return EffectiveLocation.FromScene(
                sceneId = scene.id,
                sceneName = scene.name,
                point = GeoPoint(
                    latitude = scene.latitude,
                    longitude = scene.longitude,
                    altitude = scene.altitude,
                    accuracyMeters = scene.locationAccuracy ?: Float.MAX_VALUE,
                    provider = scene.locationProvider ?: "unknown",
                    locatedAt = scene.locatedAt ?: scene.createdAt,
                ),
            )
        }
        return EffectiveLocation.SceneWithoutCoordinate(scene.id, scene.name)
    }

    // 3. 无坐标
    return EffectiveLocation.None
}
```

**解析结果矩阵**（单元测试必须覆盖全部 8 种组合）：

| 有无场景 | 场景坐标模式 | 场景有坐标 | 记录有实测坐标 | 解析结果 |
| --- | --- | --- | --- | --- |
| 无 | — | — | 有 | `Measured` |
| 无 | — | — | 无 | `None` |
| 有 | FIXED | 有 | 恒为 null | `FromScene` |
| 有 | FIXED | 无 | 恒为 null | `SceneWithoutCoordinate` |
| 有 | FOLLOW | — | 有 | `Measured`（快照） |
| 有 | FOLLOW | 有 | 无 | `FromScene`（回退） |
| 有 | FOLLOW | 无 | 无 | `SceneWithoutCoordinate` |

**`locationAccuracy` 缺失时取 `Float.MAX_VALUE` 而非 0**：0 会被后续的"精度优于 N 米"筛选误判为"精度极高"。缺失必须表现为最差，这是防御性设计。

### 5.7 坐标获取策略

**核心问题**：GPS 冷启动可能超过 30 秒。若每次保存都同步等待定位，应用会变得不可用。

**策略：缓存优先 + 按需定位 + 超时降级。**

```kotlin
class LocationProvider(private val context: Context) {
    private val manager = context.getSystemService(LocationManager::class.java)

    /** 最近一次定位，主页活跃期间持续维护 */
    private val _lastKnown = MutableStateFlow<GeoPoint?>(null)
    val lastKnown: StateFlow<GeoPoint?> = _lastKnown.asStateFlow()

    /** 主页可见期间开启低频更新，把坐标"预热"好（60s 间隔 / 50m 位移门槛） */
    @SuppressLint("MissingPermission")
    fun startTracking() { /* ... */ }

    /** 保存时调用：缓存够新直接返回，否则主动定位 */
    suspend fun acquire(timeoutMs: Long = 10_000): LocationResult {
        if (!PermissionHelper.hasLocationPermission(context)) return LocationResult.NoPermission
        if (!manager.isProviderEnabled(GPS_PROVIDER)) return LocationResult.ServicesDisabled
        _lastKnown.value
            ?.takeIf { System.currentTimeMillis() - it.locatedAt < CACHE_TTL_MS }  // 120s
            ?.let { return LocationResult.Success(it) }
        return withTimeoutOrNull(timeoutMs) { raceProviders() }
            ?.also { _lastKnown.value = it }   // 写回缓存，见下
            ?.let { LocationResult.Success(it) }
            ?: LocationResult.Timeout
    }

    /** API 30 前后两套路径 */
    private suspend fun requestCurrent(): GeoPoint? = suspendCancellableCoroutine { cont ->
        if (Build.VERSION.SDK_INT >= 30) {
            manager.getCurrentLocation(PROVIDER, null, executor, callback)
        } else {
            @Suppress("DEPRECATION")
            manager.requestSingleUpdate(PROVIDER, listener, Looper.getMainLooper())
        }
    }
}
```

**`acquire` 返回 `LocationResult` 而不是 `GeoPoint?`**：`null` 把"没权限""定位服务关着""超时"压成同一个值，于是四条调用路径只能共用一句失败文案，而这三者的处理方式完全不同——前两者点一下就能修好，超时才需要换位置。四个成员也保证每个 `when` 是穷尽的四分支。

**刻意不放 `PermissionPermanentlyDenied`**：判断"能否再次申请"要 `shouldShowRequestPermissionRationale` 与打开系统设置页，两者都需要 `Activity`，而 provider 拿的是 application context。职责分离——provider 只报"发生了什么"，UI 层（有 Activity）用纯函数判定"为什么"。

**取得的新坐标要写回 `_lastKnown`**：主页那行「磁偏角需要坐标 · 去取坐标」的判据就是 `lastKnown`。不写回的话，用户点完按钮、坐标明明取到了，屏幕上却什么都不会变。

**Provider 选择顺序**：`GPS_PROVIDER`（室外精度高）→ `NETWORK_PROVIDER`（室内可用，精度低）。两者都尝试，取先返回且精度更好的一个。

**缓存垫底要遍历全部已启用 provider**（`selectBestLastKnown`）：只看优先级最高的那一个，会在"GPS 已启用但还没定上位"时把一个 5 秒前刚更新的 NETWORK 缓存整个丢掉——而这恰恰是室内最需要垫底的场景。`locatedAt <= 0`（部分 ROM 的 `getLastKnownLocation` 返回 `time = 0`）与未来时间超容许偏差的候选一并丢弃，它们既过不了上面那道 TTL 检查，也会让"获取于"显示成 1970 年。

**各场景下的超时预算**：

| 情形 | 超时 | 理由 |
| --- | --- | --- |
| `FIXED` 场景内保存 | 0（不定位） | 坐标来自场景，无需定位 |
| `FOLLOW` 场景内保存 | 3s | 有场景坐标兜底，不必久等 |
| 无场景保存 | 10s | 没有兜底，多给一点时间 |

**超时后的行为**：保存为无坐标记录（或回退场景坐标），**不阻塞保存**，UI 明确标注。

**补录坐标**：记录详情页与场景详情页均提供。用户在室外定位良好时打开旧记录/旧场景，触发一次定位并更新。这弥补了室内采样的固有短板。

**功耗**：`startTracking` 仅在 `CompassScreen` 可见时运行，`onPause` 立即注销。60 秒间隔 + 50 米门槛的位置更新，功耗与一次普通消息推送相当。注册范围遍历全部已启用 provider 而非只注册 GPS，理由见 §9。

### 5.8 核心算法

#### (a) 方位角计算

```kotlin
val R = FloatArray(9)
val orientation = FloatArray(3)
SensorManager.getRotationMatrixFromVector(R, event.values)
SensorManager.getOrientation(R, orientation)
var azimuthDeg = Math.toDegrees(orientation[0].toDouble()).toFloat()
azimuthDeg = (azimuthDeg + 360f) % 360f
```

屏幕旋转必须重映射坐标系：

```kotlin
val (axisX, axisY) = when (display.rotation) {
    Surface.ROTATION_0   -> AXIS_X to AXIS_Y
    Surface.ROTATION_90  -> AXIS_Y to AXIS_MINUS_X
    Surface.ROTATION_180 -> AXIS_MINUS_X to AXIS_MINUS_Y
    Surface.ROTATION_270 -> AXIS_MINUS_Y to AXIS_X
    else -> AXIS_X to AXIS_Y
}
SensorManager.remapCoordinateSystem(R, axisX, axisY, Rremapped)
```

> **横屏必须支持**：用户平举手机采样时，很多机型会因重力感应转到横屏。坐标系不重映射会让方位角整体偏移 90°——一个会导致**所有记录数据错误**的严重 bug。单测必须覆盖四种 `rotation`。

#### (b) 兜底方案

```kotlin
SensorManager.getRotationMatrix(R, null, accelValues, magValues)
SensorManager.getOrientation(R, orientation)
```

#### (c) 磁场总强度

```kotlin
val magnitude = sqrt(magX * magX + magY * magY + magZ * magZ)   // μT
```

地磁参考范围：**25 μT（赤道）～ 65 μT（两极）**，中国大部分地区约 45–55 μT。

#### (d) 平滑滤波

```kotlin
const val ALPHA = 0.15f

// 先在最短弧上求差值再滤波，避免 359°→1° 时指针绕圈
fun smoothAngle(prev: Float, next: Float, alpha: Float): Float {
    var delta = ((next - prev + 540f) % 360f) - 180f   // 归一化到 (-180, 180]
    return (prev + alpha * delta + 360f) % 360f
}
```

> **关键陷阱**：直接对角度值滤波，会在 359° 与 1° 之间跳变时让指针转 358°，必须走最短弧。

磁场强度同样低通滤波，alpha 取 0.08（磁场变化缓慢）。

> **保存滤波后的值还是原始值？** 保存**滤波后**的值——它才是用户在屏幕上看到并确认的那个数。保存用户没见过的原始值会造成"存的和显示的不一致"的困惑。同时保存该时刻的 `sensorAccuracy`，精度信息不丢失。

#### (e) 磁北 → 真北

```kotlin
val geoField = GeomagneticField(lat, lon, altitude, timeMillis)
val declination = geoField.declination          // 东偏为正
val trueAzimuth = (magneticAzimuth + declination + 360f) % 360f
```

`GeomagneticField` 需要坐标。**这里与 R7/R11 产生协同**：场景坐标不仅能标注采样点，还能在室内（本机无定位）时提供磁偏角计算所需的经纬度。**因此进入带坐标的场景后，真北模式在室内依然可用**——这是场景机制的额外收益。

### 5.9 精度与校准

| accuracy 值 | 含义 | UI 表现 |
| --- | --- | --- |
| `SENSOR_STATUS_ACCURACY_HIGH` (3) | 高精度 | 绿色 |
| `SENSOR_STATUS_ACCURACY_MEDIUM` (2) | 中精度 | 黄色 |
| `SENSOR_STATUS_ACCURACY_LOW` (1) | 低精度 | 橙色 +「建议校准」 |
| `SENSOR_STATUS_UNRELIABLE` (0) | 不可信 | 红色 +「请做 8 字校准」 |

**低精度记录仍允许保存**，仅在列表与详情页显示精度徽标。用户可能就是要记录"这里磁场异常"这一事实，而异常本身会拉低精度。**阻止保存反而丢失信息。**

#### 判据：两个指标分工，不能互相替代

`accuracy` 是设备自报的结论，粒度粗且部分 ROM 上不可靠。因此从 v1.4 起另引入**未校准磁力计**（`TYPE_MAGNETIC_FIELD_UNCALIBRATED`，API 18，minSdk 24 起恒可用），用它的 `values[3..5]`（系统估计的硬磁偏置）作为「校准够不够」的独立判据：

| 指标 | 取自 | 回答的问题 |
| --- | --- | --- |
| 磁场强度是否落在 25–65 μT | **已校准**磁力计 | 附近有没有铁磁物 / 强电流 |
| 硬磁偏置 / 磁场强度 ≥ 0.5 | **未校准**磁力计的 `values[3..5]` | 校准本身够不够 |

**两者必须分开。** 偏置已经被系统从校准值里减掉了，所以判「环境里有没有铁」只能看校准后的强度；判「校准做得好不好」只能看被减掉的那部分有多大。`BIAS_RATIO_WARN = 0.5` 是**启发式**阈值，AOSP 未公开对应数值，需按真机观察调整——因此单测钉住的是边界语义（闭区间、缺失落到哪一档、迟滞方向），不是这个数本身。

该传感器是**可选**的：设备不提供时诊断少一项，罗盘照常工作，界面上不显示偏置占比（显示 0% 是错的——0 的意思是「偏置很小」，与「测不了」恰好相反）。

#### 三种成因，三种办法

原先只有一句「请画 8 字」，而**最常见的那一种**（磁吸手机壳 / 车载支架 / MagSafe 配件）画多少遍 8 字都不会好——磁铁贴在手机背面，偏置会一直很大。给错办法会让用户反复做一件永远不生效的事，然后把结论落在「这个应用不准」上。因此成因必须分开，文案随之分支（`CalibrationMessages`）：

| 成因 | 判据 | 给用户的动作 |
| --- | --- | --- |
| `NEEDS_CALIBRATION` | 偏置占比超标，或设备自报精度低 | 画 8 字 |
| `INTERFERENCE` | 强度超出地磁范围而偏置正常 | 换一个位置 |
| `MAGNETIC_ACCESSORY` | 强度异常**且**偏置同时很大 | 取下配件 |
| `NO_SIGNAL` | 读不到磁场 | 无从下手，如实说明 |

**恒定偏差不是校准问题。** 若偏差是恒定几度、换到哪儿都一样，那是磁北与真北之差（磁偏角，国内约 −10°~+5°），画多少 8 字都不会消。校准面板底部固定说明这一点并指向真北开关——这直接关系到「确保指向是正确的」，不讲明白用户会朝错误的方向使劲。

#### 交互形态：常驻提示条 + 校准模式

- **常驻提示条**：成因非 `OK` 时，主页顶部挂一条可点的黄色横幅，成因转为 `OK` 后自动消失。整条可点，文案自带「· 点此校准」「· 查看」等动词——没有可点击线索的矩形用户不会去点。
- **校准模式是主页上的一种模式，不是独立路由**。用户要一边画 8 字一边看着读数回升，那两件事必须同屏（`design-gui.md` §11「校准中」）。面板内实时显示精度、磁场强度、硬磁偏置占比三个读数，判据转为 `OK` 时立刻给出「✓ 已校准」——这是原先完全缺失的反馈回路：静态弹窗时代，用户画完点掉，毫无回音。
- **阈值迟滞**：磁场是连续量，会一直在阈值上下漂。离开某条成因需要多跨一段余量，否则提示条会一闪一闪。余量只作用在正在抱怨的那一条上——两条同时收紧会让纯粹的偏置问题被改判成环境干扰，从此再也退不出来。

**v1.4 仍只做引导与诊断，不写自有校准算法。** 硬磁/软磁椭球拟合仍属 Roadmap（§1.4）。范围守在这里：界面负责让用户知道「有没有问题、是什么问题、该做什么」，算法交给平台。

### 5.10 数据持久化

#### 数据库版本与迁移

```kotlin
@Database(
    entities = [RecordEntity::class, TagEntity::class, RecordTagCrossRef::class, SceneEntity::class],
    version = 1,
    exportSchema = true,          // 必须导出 schema，供迁移测试
)
abstract class MagnateDatabase : RoomDatabase() {
    abstract fun recordDao(): RecordDao
    abstract fun tagDao(): TagDao
    abstract fun sceneDao(): SceneDao
}
```

- `exportSchema = true`，schema JSON 纳入版本控制。v1.0 就做好，后续加字段才有迁移基线；
- 迁移使用显式 `Migration`，**禁止 `fallbackToDestructiveMigration()`**——那会在升级时静默删光用户数据。

#### 查询量级判断

个人采样工具，按每天 20 条、每年 7,000 条估算，五年 35,000 条。

**结论：不需要 FTS。** 万级数据上 `LIKE '%kw%'` 配合 JOIN 耗时在毫秒级。引入 FTS4/FTS5 会带来虚表同步与中文分词（需额外 tokenizer）的复杂度，收益为零。

分页亦不需要 Paging 3——35,000 条元数据内存占用约几 MB。使用 `LazyColumn` + 简单 limit/offset，实测卡顿再评估。

#### 关联聚合模型

```kotlin
data class RecordWithRelations(
    @Embedded val record: RecordEntity,
    @Relation(
        parentColumn = "id", entityColumn = "recordId",
        associateBy = Junction(RecordTagCrossRef::class),
    )
    val tags: List<TagEntity>,
    @Relation(parentColumn = "sceneId", entityColumn = "id")
    val scene: SceneEntity?,
) {
    /** 有效坐标由纯函数解析，不在此处缓存 */
    fun location(): EffectiveLocation = resolveLocation(record, scene)
}

data class SceneWithCount(
    @Embedded val scene: SceneEntity,
    val recordCount: Int,
)
```

#### DAO

```kotlin
@Dao
interface SceneDao {
    @Query("""
        SELECT s.*, (SELECT COUNT(*) FROM records r WHERE r.sceneId = s.id) AS recordCount
        FROM scenes s ORDER BY s.updatedAt DESC
    """)
    fun observeAll(): Flow<List<SceneWithCount>>

    @Query("SELECT * FROM scenes WHERE id = :id")
    fun observeById(id: Long): Flow<SceneEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(scene: SceneEntity): Long

    @Query("UPDATE scenes SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("""
        UPDATE scenes SET latitude = :lat, longitude = :lon, altitude = :alt,
            locationAccuracy = :acc, locationProvider = :provider,
            locatedAt = :at, updatedAt = :now
        WHERE id = :id
    """)
    suspend fun updateLocation(
        id: Long, lat: Double, lon: Double, alt: Double?,
        acc: Float, provider: String, at: Long, now: Long,
    )

    @Query("UPDATE scenes SET coordMode = :mode, updatedAt = :now WHERE id = :id")
    suspend fun updateCoordMode(id: Long, mode: CoordMode, now: Long)

    @Query("DELETE FROM scenes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM records WHERE sceneId = :id")
    fun observeRecordCount(id: Long): Flow<Int>
}

@Dao
interface RecordDao {

    @RawQuery(observedEntities = [
        RecordEntity::class, TagEntity::class, RecordTagCrossRef::class, SceneEntity::class,
    ])
    fun search(query: SupportSQLiteQuery): Flow<List<RecordWithRelations>>

    @Transaction
    @Query("SELECT * FROM records WHERE id = :id")
    fun observeById(id: Long): Flow<RecordWithRelations?>

    @Insert
    suspend fun insert(record: RecordEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun linkTags(refs: List<RecordTagCrossRef>)

    @Query("DELETE FROM records WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("""
        UPDATE records SET latitude = :lat, longitude = :lon, altitude = :alt,
            locationAccuracy = :acc, locationProvider = :provider, locatedAt = :at
        WHERE id = :id
    """)
    suspend fun updateLocation(
        id: Long, lat: Double, lon: Double, alt: Double?,
        acc: Float, provider: String, at: Long,
    )

    @Query("SELECT COUNT(*) FROM records")
    fun observeCount(): Flow<Int>
}
```

#### SQL 注入防护

`@RawQuery` 的动态 SQL 中，**任何用户输入必须走 `bindArgs` 绑定参数**。唯一可字符串拼接的是结构片段（排序字段名、排序方向），且必须来自枚举白名单：

```kotlin
val sortColumn = when (filter.sortBy) {
    SortField.TIME      -> "r.timestamp"
    SortField.MAGNITUDE -> "r.magnitude"
}
val direction = if (filter.sortAsc) "ASC" else "DESC"
```

#### 标签"同时包含全部"查询

标签筛选最容易写错的地方。要求"同时带 #室外 和 #基线"不能简单用 `IN`：

```sql
SELECT r.* FROM records r
LEFT JOIN scenes s ON s.id = r.sceneId
WHERE (:tagCount = 0 OR (
    SELECT COUNT(DISTINCT rt.tagId)
    FROM record_tags rt
    WHERE rt.recordId = r.id AND rt.tagId IN (:tagIds)
) = :tagCount)
ORDER BY r.timestamp DESC
```

- `tagMatchAll = true` → `= :tagCount`（AND 语义）
- `tagMatchAll = false` → `> 0`（OR 语义）

`COUNT(DISTINCT ...)` 保证重复关联不会误判，子查询走 `record_tags(tagId)` 与主键索引。

#### 搜索条件模型

```kotlin
data class RecordFilter(
    val keyword: String? = null,           // 匹配备注 / 标签名 / 场景名
    val tagIds: Set<Long> = emptySet(),
    val tagMatchAll: Boolean = true,
    val sceneIds: Set<Long> = emptySet(),  // 场景筛选（R11）
    val timeFrom: Long? = null,
    val timeTo: Long? = null,
    val magnitudeMin: Float? = null,
    val magnitudeMax: Float? = null,
    val hasLocation: Boolean? = null,      // 是否解析出有效坐标
    val minLocationAccuracy: Float? = null,// 只保留精度优于 N 米的
    val sortBy: SortField = SortField.TIME,
    val sortAsc: Boolean = false,
)

enum class SortField { TIME, MAGNITUDE }
```

关键词同时匹配备注、标签名与场景名（任一命中）：

```sql
AND (:kw IS NULL
     OR r.note LIKE '%' || :kw || '%'
     OR EXISTS (SELECT 1 FROM record_tags rt2 JOIN tags t ON t.id = rt2.tagId
                WHERE rt2.recordId = r.id AND t.name LIKE '%' || :kw || '%')
     OR (s.name IS NOT NULL AND s.name LIKE '%' || :kw || '%'))
```

场景筛选：

```sql
AND (:sceneCount = 0 OR r.sceneId IN (:sceneIds))
```

**"有坐标"筛选的注意事项**：这一条件的判断必须与 `resolveLocation` 的语义一致——`FIXED` 场景内的记录自身 `latitude` 为 null，但解析后**有**坐标。因此 SQL 侧要写成：

```sql
AND (:hasLoc IS NULL OR (
    :hasLoc = 1 AND (r.latitude IS NOT NULL OR (s.latitude IS NOT NULL AND r.sceneId IS NOT NULL))
 OR :hasLoc = 0 AND (r.latitude IS NULL AND (s.latitude IS NULL OR r.sceneId IS NULL))
))
```

> **这是场景机制引入的最大的一个一致性陷阱**：任何"记录有没有坐标"的判断，都不能只看 `records` 表。必须同时考虑场景继承。SQL 与 Kotlin 两处实现必须由同一个单元测试交叉验证。

#### 事务边界

保存一条记录（含标签、场景归属）必须在**单个事务**内完成：

```kotlin
@Transaction
suspend fun saveRecordWithTags(
    record: RecordEntity,
    tagNames: List<String>,
    now: Long,
): Long {
    val id = insert(record)
    val refs = tagNames.map { name ->
        val trimmed = name.trim()
        val tagId = insertTag(TagEntity(name = trimmed, createdAt = now))
            .takeIf { it != -1L }
            ?: tagDao.findByName(trimmed)!!.id      // 已存在则复用
        RecordTagCrossRef(id, tagId)
    }
    linkTags(refs)
    return id
}
```

`insertTag` 用 `IGNORE` 冲突策略：标签名有唯一索引，重复插入返回 -1，此时回查已存在的 id 复用，避免并发创建出重复标签行。

#### 激活场景的持久化

「当前激活场景」存在 DataStore（Preferences），键 `active_scene_id: Long`。应用重启后保持——用户进了 B2 层，锁屏再打开应该还在这个场景。

`activeSceneId` 指向的场景被删除时，读取处需处理悬空 ID：查不到则视为无场景并清除该键。

---

## 6. 界面设计

界面设计（页面清单、布局、组件规格、状态、视觉规范、动效、适配、无障碍）见配套文档 **[`design-gui.md`](./design-gui.md)**。

本节只保留与架构相关的界面约束：

- **七个页面**：主页 `CompassScreen`、记录列表、记录详情、搜索、标签管理、**场景列表 `SceneListScreen`**、**场景详情 `SceneDetailScreen`**。Navigation Compose 组织，主页为起始目的地；
- 界面仅依赖 Jetpack Compose + Navigation，**不引入非 AndroidX 的第三方 UI 库**。罗盘与 8 字校准动画均手绘；
- 表盘**双层结构**：刻度与方位字为一层（旋转 `-azimuthDeg`），指针与中心点为另一层（静止）；
- 表盘旋转用 **Linear 缓动**，EaseOut 会让指针停止时过冲回弹，被误读为仪器不准；
- 所有跳动数值用**等宽字体**，否则数字位数变化会导致整块文字抖动；
- 磁场进度条用**固定量程 0–100 μT**，自适应量程让用户失去"多大算大"的直觉；
- **保存对话框必须冻结读数快照**。用户填备注、选标签期间传感器仍在刷新，若存"点确定那一刻"的值，与用户点保存时看到的可能不同；
- **坐标来源必须可见**。UI 显示坐标时一律标注是「本机实测」还是「场景继承」，不得只显示一个光秃秃的经纬度；
- UI 不得自行拼装记录对象，也不得自行解析坐标来源，一律通过 `RecordRepository` 与 `resolveLocation()`。

---

## 7. 权限

| 权限 | 用途 | 必需性 |
| --- | --- | --- |
| 无 | 读取传感器 | 传感器**不需要任何权限** |
| `ACCESS_FINE_LOCATION` | 记录/场景坐标、磁偏角换算 | **可选**，运行时申请 |
| `ACCESS_COARSE_LOCATION` | 同上（粗略定位备选） | **可选**，与 FINE 一并申请 |

**存储权限不需要**：Room 数据库位于应用私有目录；未来导出走 MediaStore 或 SAF，同样无需 `WRITE_EXTERNAL_STORAGE`。

**权限策略：启动时申请一次，之后由常驻入口接管。**

> 本节在 v1.3 被**有意改写**。原策略是"不在启动时索取，首次用到时才申请"，实践下来是错的：
> 取坐标的四个入口里有两条是**打开面板即自动触发**的，那条路径没有用户手势，弹系统权限框在多数 ROM 上不可靠；
> 而剩下两条要等用户点了「获取坐标」才问，用户看到的顺序就成了"先失败一次，再被问权限"。
> 于是新装机上坐标功能看起来整个是坏的。改为启动时申请，让权限在用户第一次需要它之前就位。

启动时的申请**只在从未申请过时自动弹一次**（由持久化的"问过了"标记把关），并且先弹一个说明理由的对话框。Android 在两次拒绝后系统本身就不再弹框，若无脑每次启动都申请，用户会看到一个点了没反应的按钮——比不申请更糟。

拒绝后：
- 测量与保存功能完全正常；
- 场景可无坐标创建（只是失去坐标系能力，仍可作分组容器）；
- 保留「启用定位」入口，供用户后悔时开启（用 `shouldShowRequestPermissionRationale` 判断可否再次申请，永久拒绝则引导到系统设置）；
- 不重复弹窗骚扰。

**权限档位必须区分 FINE 与 COARSE**：Android 12+ 下只有 COARSE 时拿到的是约 ±2km 的模糊坐标，会一路流进场景坐标里且没有任何解释。COARSE 仍是**合法降级**（不拒绝使用），但主页状态行要如实写成「粗略定位 · 磁偏角精度受限」并提供一键升级到精确。判定 `FINE 未授予而 COARSE 已授予` 为 `COARSE_ONLY` 是纯函数，见 §8 的 `PermissionHelper`。

**卫星页需要 FINE**：API 29 起 `GnssStatus` 在只有 COARSE 时给出的是一个**空列表**，与"搜不到星"长得一模一样。卫星页对此显示权限横幅而不是一张空列表。

**Manifest 要求**：Android 12+ 下 `ACCESS_COARSE_LOCATION` 与 `ACCESS_FINE_LOCATION` 必须**同时申请**，只申请 FINE 会被系统忽略。

---

## 8. 项目结构

```
magnate/
├── app/
│   ├── build.gradle.kts
│   ├── schemas/                          # Room 导出的 schema JSON（纳入版本控制）
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── java/com/magnate/compass/
│       │   │   ├── MagnateApp.kt
│       │   │   ├── MainActivity.kt
│       │   │   ├── di/AppContainer.kt
│       │   │   ├── sensor/
│       │   │   │   ├── SensorRepository.kt
│       │   │   │   ├── SensorModels.kt
│       │   │   │   └── CompassMath.kt            # 纯函数
│       │   │   ├── location/
│       │   │   │   ├── LocationProvider.kt
│       │   │   │   └── GeoPoint.kt
│       │   │   ├── data/
│       │   │   │   ├── db/
│       │   │   │   │   ├── MagnateDatabase.kt
│       │   │   │   │   ├── RecordDao.kt
│       │   │   │   │   ├── TagDao.kt
│       │   │   │   │   ├── SceneDao.kt
│       │   │   │   │   └── Migrations.kt
│       │   │   │   ├── entity/
│       │   │   │   │   ├── RecordEntity.kt
│       │   │   │   │   ├── TagEntity.kt
│       │   │   │   │   ├── SceneEntity.kt        # 含 CoordMode
│       │   │   │   │   └── RecordTagCrossRef.kt
│       │   │   │   ├── RecordWithRelations.kt    # @Relation 聚合
│       │   │   │   ├── LocationResolver.kt       # resolveLocation 纯函数 ★
│       │   │   │   ├── RecordFilter.kt
│       │   │   │   ├── RecordQueryBuilder.kt     # 动态 SQL 构造（纯函数）★
│       │   │   │   ├── RecordRepository.kt       # 唯一写入入口
│       │   │   │   ├── SceneRepository.kt
│       │   │   │   └── ActiveSceneStore.kt       # DataStore 封装
│       │   │   ├── ui/
│       │   │   │   ├── nav/MagnateNavHost.kt
│       │   │   │   ├── compass/
│       │   │   │   │   ├── CompassScreen.kt
│       │   │   │   │   ├── CompassViewModel.kt
│       │   │   │   │   ├── CompassDial.kt
│       │   │   │   │   ├── MagneticFieldCard.kt
│       │   │   │   │   ├── SceneBar.kt           # 场景条 ★
│       │   │   │   │   └── SaveRecordSheet.kt
│       │   │   │   ├── records/
│       │   │   │   │   ├── RecordListScreen.kt
│       │   │   │   │   ├── RecordListItem.kt
│       │   │   │   │   ├── RecordDetailScreen.kt
│       │   │   │   │   └── RecordsViewModel.kt
│       │   │   │   ├── scenes/
│       │   │   │   │   ├── SceneListScreen.kt
│       │   │   │   │   ├── SceneDetailScreen.kt
│       │   │   │   │   ├── SceneEditSheet.kt
│       │   │   │   │   ├── ScenePickerSheet.kt   # 主页快速切换
│       │   │   │   │   └── SceneViewModel.kt
│       │   │   │   ├── search/
│       │   │   │   │   ├── SearchScreen.kt
│       │   │   │   │   ├── FilterSheet.kt
│       │   │   │   │   └── SearchViewModel.kt
│       │   │   │   ├── tags/
│       │   │   │   ├── common/
│       │   │   │   │   ├── LocationBadge.kt      # 坐标来源徽标 ★
│       │   │   │   │   ├── AccuracyBadge.kt
│       │   │   │   │   └── EmptyState.kt
│       │   │   │   └── theme/
│       │   │   └── util/
│       │   │       ├── PermissionHelper.kt
│       │   │       ├── TimeFormat.kt
│       │   │       └── GeoFormat.kt              # 坐标/精度格式化（纯函数）
│       │   └── res/
│       ├── test/java/com/magnate/compass/
│       │   ├── CompassMathTest.kt                # 纯 JVM
│       │   ├── LocationResolverTest.kt           # ★ 8 种组合全覆盖
│       │   ├── RecordQueryBuilderTest.kt         # ★ 含"有坐标"筛选与场景交集
│       │   └── GeoFormatTest.kt
│       └── androidTest/java/com/magnate/compass/
│           ├── RecordDaoTest.kt
│           ├── SceneDaoTest.kt                   # ★ SET_NULL 级联行为
│           └── MigrationTest.kt
├── design.md
├── design-gui.md
└── req.txt
```

**可测性设计**：`CompassMath`、`LocationResolver`、`RecordQueryBuilder`、`GeoFormat`、`TimeFormat` 全部为**纯函数**，不依赖 `Context` 或 Android 类，可在 JVM 上直接单测。

其中 `LocationResolver` 与 `RecordQueryBuilder` 是最需要测试的两个模块——它们是坐标语义的 Kotlin 侧实现与 SQL 侧实现，**必须由同一组用例交叉验证两者结论一致**。

---

## 9. 生命周期与性能

```kotlin
LifecycleResumeEffect(Unit) {
    viewModel.startListening()          // 注册传感器 + 启动定位缓存维护
    onPauseOrDispose { viewModel.stopListening() }
}
```

| 项 | 策略 |
| --- | --- |
| 采样率 | `SENSOR_DELAY_GAME`（约 50 Hz），UI 显示节流到 20 Hz |
| 定位频率 | 60s 间隔 / 50m 位移门槛，**仅主页可见时**启用；注册**全部已启用 provider**，不只 GPS（见下） |
| **FIXED 模式保存** | **完全不触发定位**，保存耗时 < 100ms |
| 后台 | `onPause` 立即注销传感器与定位，**零后台耗电** |
| **卫星页** | 只在页面可见时持有保活定位请求，**离开即注销**（见下） |
| 重组优化 | 罗盘、磁场卡片、场景条各自订阅独立状态，避免全页重组 |
| 列表性能 | `LazyColumn` + 稳定 `key = record.id`；数据类标 `@Immutable` |
| 数据库 | 所有 DB 操作在 `Dispatchers.IO`；Room `Flow` 查询自动在后台线程 |
| 搜索节流 | 搜索框 `debounce(300ms)` + `distinctUntilChanged()`，避免每键查库 |

### 9.1 两处对"零后台耗电"的修订（v1.3）

**主页预热改为遍历全部已启用 provider。** 原实现只注册 `GPS_PROVIDER`，于是室内"GPS 已启用但定不上位"的机器永远预热不出新坐标——而这个功能存在的意义恰恰是这个场景。代价是多一路 60s/50m 的注册，与原来同量级。`stopTracking()` 无需改动（`removeUpdates(listener)` 会移除该 listener 的全部请求），且只在主页 resume 期间发生。

**卫星页自己持有一个保活定位请求。** 这不是选择而是平台事实：**注册 `GnssStatus.Callback` 不会启动 GNSS 引擎**，引擎只在存在活跃定位请求时运转，`onSatelliteStatusChanged` 也只在那时才回调。不持有请求的话，屏幕上永远是"尚未搜到卫星"——看起来和设备坏了没有任何区别。

零后台耗电仍然成立，但保证方式变了：卫星流是**冷流**（`callbackFlow`），每个订阅者独立注册、取消订阅即注销，配合 `WhileSubscribed(0)` 使用。于是"可见才注册"是**结构性的**，不是一条需要记得遵守的纪律——离开页面保活请求就没了，不需要任何 `onPause` 钩子。订阅停止的延迟刻意取 0 而非项目惯用的 5000ms：那 5 秒宽限期是为"转屏、短暂遮挡"这类瞬断准备的，而这里任何延迟都会让 GPS 请求在页面已不可见之后继续存活。

卫星页的请求**不复用 `startTracking()`**：那边的 `tracking` 是普通布尔量而不是引用计数（刻意的），两个拥有者共享一个布尔量时，后注销的那个会把另一个留在没注册的状态。同一应用对同一 provider 的两次 `requestLocationUpdates` 本来就是互相独立的注册，引擎按请求的并集运转，各管各的注销才是对的。两者实际上也不会重叠：导航会把 `CompassScreen` 移出组合，其 `LifecycleResumeEffect` 先触发 `stopTracking()`，唯一重叠是转场那一瞬，独立注册让它是无害的。

---

## 10. 异常与边界处理

| 场景 | 处理 |
| --- | --- |
| 设备无磁力计 | 整页空状态，**但记录/场景功能保留**（用户可能要看别的设备采的数据） |
| 设备无陀螺仪 | 降级到 `TYPE_GEOMAGNETIC_ROTATION_VECTOR` |
| 无旋转矢量且无陀螺仪 | 降级到加速度计 + 磁力计 |
| 读数偏离 25–65 μT | 常驻提示条区分「环境干扰」与「磁吸配件」，两者动作不同（§5.9） |
| 传感器精度 UNRELIABLE | 提示校准；**仍允许保存**，记录带精度徽标 |
| 硬磁偏置占比超标 | 同「需要校准」，与精度读数**互为独立判据**（§5.9） |
| 设备无未校准磁力计 | 少一项判据，不显示偏置占比（**不显示 0**），其余照常 |
| 偏差恒定且到处一样 | 说明那是磁偏角而非校准问题，指向真北开关（§5.9） |
| 定位权限被拒 | 记录/场景不含坐标，标注「无坐标」，保留补录入口 |
| 定位超时 | 按 §5.7 超时预算降级，**不阻塞保存**；Snackbar 挂「查看卫星」让原因当场可查 |
| 定位服务关闭 | `isProviderEnabled` 为 false 时直接跳过定位，不等超时 |
| **卫星数据受阻** | 权限不足 / 定位服务关闭 / 设备无 GNSS，各给一句不同的横幅。**受阻时引擎状态栏显示「—」而非「引擎未启动」**——后者会把权限问题说成硬件问题，与横幅打架 |
| 只有粗略定位权限 | 记录/场景仍可用（约 ±2km），但主页状态行明确标注「粗略定位 · 磁偏角精度受限」并提供升级入口；**卫星页直接显示权限横幅**，因为 API 29+ 下 COARSE 得到的是空列表，与"搜不到星"无法区分 |
| **场景无坐标** | 记录解析为 `SceneWithoutCoordinate`，UI 提示「场景未设置坐标」+ 补录入口 |
| **删除场景** | 记录 `sceneId` 置 null，**记录不删**；确认对话框明确告知 |
| **删除激活中的场景** | 自动退出该场景（清除 DataStore 键），主页场景条回到「未选择场景」 |
| **activeSceneId 悬空** | 查不到场景时视为无场景并清除该键 |
| **场景重名** | 唯一索引冲突，提示「场景名已存在」并提供跳转 |
| **修改 FIXED 场景坐标** | 确认对话框告知影响记录数；提供 5 秒撤销 |
| 数据库升级 | 显式 Migration，**禁止破坏性迁移** |
| 标签重名 | 唯一索引 + `IGNORE`，复用已有标签 |
| 删除记录 | Snackbar 提供 5 秒撤销 |

**关于删除撤销**：Room 无内置回收站。实现方式：删除时把整条记录（含标签关联）暂存内存，Snackbar 期间不执行真实删除；超时后落库。用户点撤销则取消删除任务。这比维护 `is_deleted` 软删除列简单，且不污染所有查询。

---

## 11. 测试策略

| 层级 | 内容 |
| --- | --- |
| **单元测试（JVM）** | `CompassMathTest`：角度归一化、最短弧滤波（**359°→1° 与 1°→359° 双向边界**）、磁场模长、方向名称 22.5° 分界、**四种屏幕 rotation 重映射** |
| | `LocationResolverTest`：**§5.6 矩阵的全部 7 种组合**；尤其「FOLLOW + 有实测」必须返回 `Measured` 而非 `FromScene`；`locationAccuracy` 缺失时必须是 `MAX_VALUE` 而非 0 |
| | `RecordQueryBuilderTest`：每种筛选单独验证；标签 AND/OR；场景交集；**「有坐标」筛选必须覆盖场景继承情形**（记录自身无坐标但场景有 → 应命中）；关键词特殊字符（`%` `_` `'`）不破坏 SQL；排序白名单 |
| | `GeoFormatTest`：坐标格式化（含南纬/西经负值）、定位精度分档 |
| **仪器测试** | `SceneDaoTest`：**删除场景后记录仍存在且 `sceneId` 为 null**（SET_NULL 行为）；场景重名冲突；`recordCount` 子查询正确性 |
| | `RecordDaoTest`：标签 AND 查询、唯一索引冲突复用、标签 CASCADE 删除、事务原子性（插入中途失败不留半条记录） |
| | `MigrationTest`：`MigrationTestHelper` 验证迁移后数据完整 |
| **手动测试** | 真机对照实体指南针（误差 < 5°）；靠近磁铁验证干扰告警；旋转屏幕验证方位角不偏移；**完整走一遍"户外取坐标 → 建场景 → 室内连测 10 点 → 验证 10 条记录坐标一致且标注为场景来源"** |

**验收标准**：

1. 与实体指南针比对，方位角误差 ≤ 5°；
2. 磁场强度与当地地磁参考值误差 ≤ 10%；
3. 读数刷新流畅无跳字，静止时指针抖动 < 1°；
4. 无磁力计设备不崩溃，显示明确提示；
5. 后台时 `dumpsys batterystats` 显示无传感器/定位唤醒；
6. 保存一条记录到出现在列表中 < 500ms（不含定位等待）；
7. **`FIXED` 场景内保存 < 100ms**，全程无定位调用；
8. **定位失败时保存流程仍在 1 秒内完成**；
9. **标签 AND 筛选准确**：记录含 [#A,#B,#C]，筛 [#A,#B] 命中，筛 [#A,#D] 不命中；
10. **场景坐标继承准确**：FIXED 场景下 10 条记录解析出的经纬度**完全一致**，且来源标注为「场景」；
11. **删除场景后其记录全部保留**，`sceneId` 为 null，无数据丢失；
12. **「有坐标」筛选与 `resolveLocation` 结论一致**（交叉验证用例通过）；
13. 10,000 条记录下搜索响应 < 300ms；
14. 删除记录后撤销，数据完整恢复（含标签关联）。

---

## 12. 风险与对策

| 风险 | 影响 | 对策 |
| --- | --- | --- |
| 手机内部磁性元件零点偏移 | 方位角系统性偏差 | 引导 8 字校准；文档说明手机壳磁扣是常见干扰源 |
| 靠近铁磁性物体 | 读数失真 | 范围检测 + 干扰告警 |
| 厂商传感器实现差异大 | 部分机型表现异常 | 降级链 + 真机覆盖（至少 3 个品牌） |
| **国产 ROM 无 GMS** | 若依赖 Fused API 则定位全废 | **已规避**：使用系统 `LocationManager`（§3.1） |
| **室内采样拿不到 GPS** | 记录缺坐标 | **场景机制根本性缓解**（§1.3）；另有缓存预热 + 超时降级 + 补录 |
| **坐标精度参差** | 用户误信低精度坐标 | 必存 `locationAccuracy`，UI 显式展示精度等级 |
| **场景坐标被误改** | 历史记录坐标静默变化 | 变更前告知影响记录数；变更后 5 秒撤销；详情页显示坐标更新时间 |
| **场景与标签概念重叠** | 用户困惑，重复建两套体系 | 文档与 UI 文案明确区分（§1.3）；场景选择器与标签选择器视觉风格区分 |
| **场景数量膨胀** | 选择器难用 | 场景列表按 `updatedAt` 排序；主页场景条显示最近使用；待确认问题 §14.3 讨论上限 |
| 定位权限被永久拒绝 | 无法补录 | 引导至系统设置；核心功能不受影响 |
| 数据库升级丢数据 | 用户记录永久丢失 | 显式 Migration + `MigrationTest` + 禁用破坏性迁移 |

---

## 13. Roadmap

| 版本 | 内容 |
| --- | --- |
| v1.0 | 方位角 + 磁场实时显示、罗盘表盘、精度提示、真北可选 |
| v1.1 | 采样点坐标、记录保存、标签、搜索与筛选 |
| **v1.2** | **场景：坐标挂载、场景内自动归属、场景管理与切换**（本文档范围） |
| v1.3 | 数据导出（CSV / GeoJSON）、**地图视图**（场景为点、FOLLOW 记录为轨迹） |
| v1.4 | 椭球拟合硬磁/软磁校准（自研校准算法） |
| v2.0 | **磁场热力图**（以场景为空间单元，场景内插值）、金属探测模式、多设备对比 |

> **场景是 v2.0 热力图的空间骨架。** 热力图需要"一个位置、多个测量值"的结构，而 FIXED 场景恰好就是这个结构——同一坐标下聚集了该位置的全部测量点。**如果热力图在规划内，场景的 `coordMode` 从 v1.2 就要实现正确**：只有 FIXED 场景才能聚合成热力点，FOLLOW 场景是散点轨迹，两者在可视化上完全不同。

---

## 14. 待确认问题

1. **一个场景是否允许多个坐标点？**
   当前设计是「一个场景 = 一个坐标」。若需要「一栋楼的不同楼层各有一个坐标」，则需要场景嵌套或场景内多坐标点。这会显著增加模型与 UI 复杂度，需要明确是否需要。
2. **是否需要「场景模板」或「场景复制」？**
   多次测量同一地点时，复制场景（名称 + 坐标，清空记录）会很省事。
3. **场景数量上限？**
   当前无硬上限。若预期超过 50 个，主页的快速选择器需要改为可搜索列表。
4. **采样场景是室内还是室外为主？**
   室内为主 → `FIXED` 是绝对主力，可考虑隐藏 `FOLLOW` 模式以降复杂度；室外为主 → `FOLLOW` 需要加强（更高定位频率、轨迹展示）。
5. **场景是否需要标签？**
   当前场景与标签正交，场景本身无标签。若需要「按标签筛选场景」，需新增场景-标签关联表。
6. **是否需要数据导出？**（CSV / GeoJSON / KML）若需要，需确认导出字段与格式。
7. **预期记录量级？** 本设计按「个人使用、万级以内」假设，故未引入 FTS 与 Paging。
8. **是否有精度指标要求？**（如 ±2°）若有，需引入更复杂的滤波算法。
9. **UI 语言**：仅中文，还是中英双语？
