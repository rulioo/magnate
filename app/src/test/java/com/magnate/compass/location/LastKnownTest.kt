package com.magnate.compass.location

import com.magnate.compass.point
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 缓存垫底的选取规则。
 *
 * 原实现只看优先级最高的那个 provider（GPS），于是「GPS 开着但还没定上位」时，
 * 一个刚刷新过的 NETWORK 缓存会被整个忽略——而那恰是室内最需要垫底的场景。
 * 这里的断言把「该看全部 provider」和「哪些缓存必须丢掉」都钉住。
 */
class LastKnownTest {

    private val now = 1_709_620_200_000L
    private val minute = 60_000L

    @Test
    fun `多个 provider 都有缓存时取最新的那个`() {
        val older = point(provider = "gps", locatedAt = now - 10 * minute)
        val newer = point(provider = "network", locatedAt = now - minute)

        // 精度相反地设置：新的那个精度更差，若实现按精度取就会答错，
        // 从而把「取最新」与「取最准」这两条规则混为一谈
        assertSame(
            selectBestLastKnown(listOf(older, newer), now),
            newer,
        )
    }

    @Test
    fun `时间相同时取精度更好的那个`() {
        val coarse = point(accuracy = 2000f, locatedAt = now - minute)
        val precise = point(accuracy = 6f, locatedAt = now - minute)

        // 注意参数顺序：把精度差的放前面，才能区分「真的比较了精度」与「碰巧取了第一个」
        assertSame(selectBestLastKnown(listOf(coarse, precise), now), precise)
        assertSame(selectBestLastKnown(listOf(precise, coarse), now), precise)
    }

    @Test
    fun `时间为 0 的缓存被丢弃`() {
        // 部分 ROM 的 getLastKnownLocation 会返回 time = 0 的位置。
        // 留着它，界面上的「获取于」会显示成 1970 年，而且它永远过不了 TTL 检查
        val broken = point(locatedAt = 0L)
        val usable = point(locatedAt = now - 5 * minute)

        assertSame(selectBestLastKnown(listOf(broken, usable), now), usable)
        assertNull(selectBestLastKnown(listOf(broken), now))
    }

    @Test
    fun `时间戳超前过多的缓存被丢弃`() {
        // 系统时钟被改过、或 provider 实现有 bug 时会出现未来时间。
        // 留着它会让这个缓存**永远**被认为「刚刚获取」，从而一直胜出、再也不去真正定位
        val future = point(locatedAt = now + 10 * minute)

        assertNull(selectBestLastKnown(listOf(future), now))
    }

    @Test
    fun `超前在容许范围内的缓存仍然可用`() {
        // 容许一点偏差，是因为「现在」由调用方传入、而系统缓存的写入时刻
        // 与这次读取之间本来就有毫秒级错位。一刀切成 0 容差会误杀刚写入的缓存
        val slightlyAhead = point(locatedAt = now + 1_000L)

        assertSame(selectBestLastKnown(listOf(slightlyAhead), now), slightlyAhead)
    }

    @Test
    fun `精度缺失的哨兵值不会让它赢得平局`() {
        // Float.MAX_VALUE 是 resolveLocation 为缺失精度填的哨兵。
        // 它是最大的浮点数，若实现按「精度数值大者胜」写就会误选它；
        // 这里确保「不知道精度」永远赢不了「知道且精度好」
        val unknownAccuracy = point(accuracy = Float.MAX_VALUE, locatedAt = now - minute)
        val known = point(accuracy = 8f, locatedAt = now - minute)

        assertSame(selectBestLastKnown(listOf(unknownAccuracy, known), now), known)
    }

    @Test
    fun `没有任何候选时返回 null`() {
        assertNull(selectBestLastKnown(emptyList(), now))
    }
}
