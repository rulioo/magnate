package com.magnate.compass.data

import com.magnate.compass.data.entity.CoordMode
import com.magnate.compass.point
import com.magnate.compass.record
import com.magnate.compass.scene
import com.magnate.compass.sceneWithCoordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 坐标语义的矩阵测试。
 *
 * 这个文件是 [resolveLocation] 的**唯一权威验证点**：SQL 侧的 `hasLocation` 筛选
 * （见 [RecordQueryBuilderTest]）必须与这里推导出的结论一致，两套实现靠同一组用例对齐。
 */
class LocationResolverTest {

    private val eps = 1e-9

    // —— resolveLocation 的完整矩阵 ——

    @Test
    fun `记录自身有坐标时返回 Measured 无论场景如何`() {
        val r = record(latitude = 30.1, longitude = 120.2, locationAccuracy = 8f)
        listOf(
            null,
            sceneWithCoordinate(coordMode = CoordMode.FIXED),
            sceneWithCoordinate(coordMode = CoordMode.FOLLOW),
            scene(id = 100L),
        ).forEach { s ->
            val resolved = resolveLocation(r, s)
            assertTrue("场景=$s 时应为 Measured，实际 $resolved", resolved is EffectiveLocation.Measured)
        }
    }

    @Test
    fun `记录无坐标且场景有坐标时返回 FromScene 并带上场景名`() {
        val s = sceneWithCoordinate(id = 7L, name = "阳光大厦 B2层")
        val resolved = resolveLocation(record(sceneId = 7L), s)

        assertTrue(resolved is EffectiveLocation.FromScene)
        resolved as EffectiveLocation.FromScene
        assertEquals(7L, resolved.sceneId)
        assertEquals("阳光大厦 B2层", resolved.sceneName)
        assertEquals(30.123456, resolved.point.latitude, eps)
        assertEquals(120.567890, resolved.point.longitude, eps)
    }

    @Test
    fun `记录无坐标且场景无坐标时返回 SceneWithoutCoordinate`() {
        val resolved = resolveLocation(record(sceneId = 100L), scene(id = 100L, name = "会议室"))

        assertTrue(resolved is EffectiveLocation.SceneWithoutCoordinate)
        resolved as EffectiveLocation.SceneWithoutCoordinate
        assertEquals(100L, resolved.sceneId)
        assertEquals("会议室", resolved.sceneName)
    }

    @Test
    fun `记录无坐标且无场景时返回 None`() {
        assertEquals(EffectiveLocation.None, resolveLocation(record(), null))
    }

    @Test
    fun `FOLLOW 场景下实测坐标优先于场景坐标`() {
        // 这是 FOLLOW 模式的全部意义所在：拿到实测值就用实测值，
        // 场景坐标只是定位失败时的兜底。若这里返回 FromScene，说明优先级写反了。
        val r = record(sceneId = 100L, latitude = 30.111111, longitude = 120.222222)
        val s = sceneWithCoordinate(id = 100L, coordMode = CoordMode.FOLLOW)

        val resolved = resolveLocation(r, s)
        assertTrue("FOLLOW 模式有实测坐标时必须返回 Measured", resolved is EffectiveLocation.Measured)
        assertEquals(30.111111, (resolved as EffectiveLocation.Measured).point.latitude, eps)
    }

    @Test
    fun `只有纬度没有经度不算有坐标`() {
        // 半截坐标比没有坐标更危险：它会被当成有效坐标渲染到地图上
        val resolved = resolveLocation(record(latitude = 30.1, longitude = null), null)
        assertEquals(EffectiveLocation.None, resolved)
    }

    @Test
    fun `只有经度没有纬度不算有坐标`() {
        assertEquals(EffectiveLocation.None, resolveLocation(record(latitude = null, longitude = 120.2), null))
    }

    // —— 缺失精度的哨兵值 ——

    @Test
    fun `缺失精度解析为 Float MAX_VALUE 而不是 0`() {
        // 0 会被「精度优于 N 米」的筛选误判为精度极高，是最坏的一种错法
        val own = resolveLocation(record(latitude = 30.1, longitude = 120.2), null)
        assertEquals(Float.MAX_VALUE, (own as EffectiveLocation.Measured).point.accuracyMeters, 0f)

        val fromScene = resolveLocation(
            record(),
            scene(id = 100L, latitude = 30.1, longitude = 120.2, locationAccuracy = null),
        )
        assertEquals(Float.MAX_VALUE, (fromScene as EffectiveLocation.FromScene).point.accuracyMeters, 0f)
    }

    @Test
    fun `缺失定位来源解析为 unknown 而不是 null`() {
        val own = resolveLocation(record(latitude = 30.1, longitude = 120.2), null)
        assertEquals("unknown", (own as EffectiveLocation.Measured).point.provider)
    }

    @Test
    fun `缺失定位时刻回退到记录时间戳`() {
        val timestamp = 1_700_000_000_000L
        val own = resolveLocation(record(timestamp = timestamp, latitude = 30.1, longitude = 120.2), null)
        assertEquals(timestamp, (own as EffectiveLocation.Measured).point.locatedAt)
    }

    @Test
    fun `场景缺失定位时刻回退到场景创建时间`() {
        val s = scene(id = 100L, latitude = 30.1, longitude = 120.2, locatedAt = null)
        val fromScene = resolveLocation(record(), s) as EffectiveLocation.FromScene
        assertEquals(s.createdAt, fromScene.point.locatedAt)
    }

    // —— pointOrNull ——

    @Test
    fun `pointOrNull 只在两种有坐标的来源下非空`() {
        assertTrue(resolveLocation(record(latitude = 30.1, longitude = 120.2), null).pointOrNull != null)
        assertTrue(resolveLocation(record(), sceneWithCoordinate()).pointOrNull != null)
        assertNull(resolveLocation(record(), scene(id = 100L)).pointOrNull)
        assertNull(resolveLocation(record(), null).pointOrNull)
    }

    // —— ownLocationToPersist：写入侧的引用 / 快照语义 ——

    @Test
    fun `FIXED 场景有坐标时记录不存自己的坐标`() {
        // 引用语义：改场景坐标要能同步影响该场景下所有历史记录
        val s = sceneWithCoordinate(coordMode = CoordMode.FIXED)
        assertNull(ownLocationToPersist(s, point()))
    }

    @Test
    fun `FOLLOW 场景有坐标时记录存下实测坐标`() {
        // 快照语义：记录写入后不再随场景改变
        val s = sceneWithCoordinate(coordMode = CoordMode.FOLLOW)
        val measured = point(latitude = 30.111111, longitude = 120.222222)
        assertEquals(measured, ownLocationToPersist(s, measured))
    }

    @Test
    fun `场景没有坐标时记录存下实测坐标`() {
        val s = scene(id = 100L)
        val measured = point()
        assertEquals(measured, ownLocationToPersist(s, measured))
    }

    @Test
    fun `无场景时记录存下实测坐标`() {
        val measured = point()
        assertEquals(measured, ownLocationToPersist(null, measured))
    }

    @Test
    fun `实测为 null 时返回 null`() {
        assertNull(ownLocationToPersist(null, null))
        assertNull(ownLocationToPersist(sceneWithCoordinate(coordMode = CoordMode.FOLLOW), null))
    }

    // —— previewLocation：预览必须与实际写入走同一段逻辑 ——

    @Test
    fun `FIXED 场景预览忽略实测坐标`() {
        // 该模式压根不定位，预览显示一个不会落库的坐标会误导用户
        val s = sceneWithCoordinate(id = 100L, name = "B2层", coordMode = CoordMode.FIXED)
        val preview = previewLocation(s, point(latitude = 30.999999, longitude = 120.999999))

        assertTrue(preview is EffectiveLocation.FromScene)
        assertEquals(30.123456, (preview as EffectiveLocation.FromScene).point.latitude, eps)
    }

    @Test
    fun `FOLLOW 场景预览显示实测坐标`() {
        val s = sceneWithCoordinate(id = 100L, coordMode = CoordMode.FOLLOW)
        val preview = previewLocation(s, point(latitude = 30.111111, longitude = 120.222222))

        assertTrue(preview is EffectiveLocation.Measured)
        assertEquals(30.111111, (preview as EffectiveLocation.Measured).point.latitude, eps)
    }

    @Test
    fun `FOLLOW 场景但没实测到时预览回退场景坐标`() {
        val s = sceneWithCoordinate(id = 100L, coordMode = CoordMode.FOLLOW)
        val preview = previewLocation(s, null)

        assertTrue(preview is EffectiveLocation.FromScene)
        assertEquals(100L, (preview as EffectiveLocation.FromScene).sceneId)
    }

    @Test
    fun `无场景且没实测到时预览为 None`() {
        assertEquals(EffectiveLocation.None, previewLocation(null, null))
    }

    @Test
    fun `场景无坐标且没实测到时预览为 SceneWithoutCoordinate`() {
        val s = scene(id = 5L, name = "临时")
        val preview = previewLocation(s, null)

        assertTrue(preview is EffectiveLocation.SceneWithoutCoordinate)
        assertEquals(5L, (preview as EffectiveLocation.SceneWithoutCoordinate).sceneId)
    }

    @Test
    fun `预览与实际写入逐字段一致`() {
        // 防止将来有人把手写的 if-else 塞回 previewLocation：那时预览与实际写入
        // 会分叉，「显示场景坐标、存下来却是本机坐标」这类偏差极难在事后发现。
        // 这里比对的是坐标点本身而非类型，字段级不一致也能挡住。
        val scenes = listOf(
            null,
            sceneWithCoordinate(coordMode = CoordMode.FIXED),
            sceneWithCoordinate(coordMode = CoordMode.FOLLOW),
            scene(id = 100L),
        )
        val measuredValues = listOf(null, point(), point(accuracy = 2000f, provider = "network"))

        scenes.forEach { s ->
            measuredValues.forEach { measured ->
                val persisted = ownLocationToPersist(s, measured)
                val written = resolveLocation(
                    record(
                        sceneId = s?.id,
                        latitude = persisted?.latitude,
                        longitude = persisted?.longitude,
                        altitude = persisted?.altitude,
                        locationAccuracy = persisted?.accuracyMeters,
                        locationProvider = persisted?.provider,
                        locatedAt = persisted?.locatedAt,
                    ),
                    s,
                )
                val preview = previewLocation(s, measured)
                assertEquals("场景=$s 实测=$measured 类型不符", written::class, preview::class)
                assertEquals("场景=$s 实测=$measured 坐标不符", written.pointOrNull, preview.pointOrNull)
            }
        }
    }

    // —— locationPolicyFor ——

    @Test
    fun `FIXED 场景有坐标时不定位`() {
        val policy = locationPolicyFor(
            scene = sceneWithCoordinate(coordMode = CoordMode.FIXED),
            noSceneTimeoutMs = 10_000L,
            followSceneTimeoutMs = 3_000L,
        )
        assertEquals(LocationPolicy.Skip, policy)
    }

    @Test
    fun `FOLLOW 场景有坐标时用较短的超时`() {
        val policy = locationPolicyFor(
            scene = sceneWithCoordinate(coordMode = CoordMode.FOLLOW),
            noSceneTimeoutMs = 10_000L,
            followSceneTimeoutMs = 3_000L,
        )
        assertEquals(LocationPolicy.Acquire(3_000L), policy)
    }

    @Test
    fun `无场景时用较长超时`() {
        val policy = locationPolicyFor(null, noSceneTimeoutMs = 10_000L, followSceneTimeoutMs = 3_000L)
        assertEquals(LocationPolicy.Acquire(10_000L), policy)
    }

    @Test
    fun `场景无坐标时用较长超时`() {
        // 没有场景坐标兜底，失败了就真没坐标了，值得多等一会儿
        val policy = locationPolicyFor(
            scene = scene(id = 100L),
            noSceneTimeoutMs = 10_000L,
            followSceneTimeoutMs = 3_000L,
        )
        assertEquals(LocationPolicy.Acquire(10_000L), policy)
    }

    @Test
    fun `FIXED 场景但没有坐标时仍然定位`() {
        // FIXED 只是「不做逐条定位」，前提是场景自己已经有坐标
        val policy = locationPolicyFor(
            scene = scene(id = 100L, coordMode = CoordMode.FIXED),
            noSceneTimeoutMs = 10_000L,
            followSceneTimeoutMs = 3_000L,
        )
        assertEquals(LocationPolicy.Acquire(10_000L), policy)
    }
}
