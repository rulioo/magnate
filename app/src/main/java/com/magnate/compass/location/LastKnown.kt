package com.magnate.compass.location

/** `getLastKnownLocation` 返回的时间戳允许超出「现在」多少。见 [selectBestLastKnown]。 */
private const val DEFAULT_MAX_CLOCK_SKEW_MS = 60_000L

/**
 * 从若干个 provider 的系统缓存里挑一个最好的来垫底。
 *
 * 纯函数，无 Android 依赖，可 JVM 单测。
 *
 * **为什么要看多个 provider。** 原先只看优先级最高的那一个（GPS）：
 * 于是「GPS 开着但还没定上位」时，一个 5 秒前刚刷新的 NETWORK 缓存会被整个忽略——
 * 而这恰恰是室内最需要垫底的场景。
 *
 * **为什么要筛时间戳。** 部分 ROM 的 `getLastKnownLocation` 会返回 `time = 0` 的位置：
 * 它既过不了 [LocationProvider.acquire] 的 TTL 检查，又会让界面上的「获取于」显示成 1970 年。
 * 未来的时间戳同样要丢——系统时钟被改过或 provider 实现有 bug 时都会出现，
 * 留着它会让这个缓存**永远**被认为「刚刚获取」，从而一直胜出、再也不去真正定位。
 *
 * @param candidates 各 provider 缓存里的位置，允许含 null 已被调用方滤掉。
 * @param now 当前时间，由调用方传入以便测试（纯函数不自己读时钟）。
 * @param maxClockSkewMs 容许的超前量。
 */
fun selectBestLastKnown(
    candidates: List<GeoPoint>,
    now: Long,
    maxClockSkewMs: Long = DEFAULT_MAX_CLOCK_SKEW_MS,
): GeoPoint? = candidates
    .filter { it.locatedAt > 0L && it.locatedAt <= now + maxClockSkewMs }
    .maxWithOrNull(
        compareBy<GeoPoint> { it.locatedAt }
            // 时间相同时取精度更好的那个。注意 accuracyMeters 越小越好，
            // 所以这里用 descending——而缺失精度填的哨兵值 Float.MAX_VALUE 是最大数，
            // 在降序里排最末，天然不会靠「不知道精度」赢得平局。
            .thenByDescending { it.accuracyMeters }
    )
