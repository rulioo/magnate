package com.magnate.compass.util

import com.magnate.compass.location.LocationResult
import com.magnate.compass.point
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 失败文案的契约。
 *
 * 第一条测试就是这次要修的回归本身：原先四条失败路径全都写「请到窗边或室外再试」，
 * 而当时的真实主因是应用从未申请过权限。用户照着提示走到室外站到天亮也不会好。
 * 这个文件的存在就是为了让那种写法再也回不来。
 */
class LocationMessagesTest {

    private val granted = LocationPermissionState.GRANTED
    private val canAsk = LocationPermissionState.DENIED_CAN_ASK
    private val permanentlyDenied = LocationPermissionState.DENIED_PERMANENTLY

    @Test
    fun `只有超时才提示到窗边或室外`() {
        val nonTimeout = listOf(
            LocationResult.NoPermission to canAsk,
            LocationResult.NoPermission to permanentlyDenied,
            LocationResult.ServicesDisabled to granted,
            LocationResult.ServicesDisabled to permanentlyDenied,
        )

        nonTimeout.forEach { (result, state) ->
            val message = locationFailureMessage(result, state)
            assertFalse(
                "「$result」不是超时，不该把用户支使到室外去：$message",
                message!!.contains("室外"),
            )
            assertFalse("「$result」不该提窗边：$message", message.contains("窗边"))
        }
    }

    @Test
    fun `超时才提示到窗边或室外`() {
        val message = locationFailureMessage(LocationResult.Timeout, granted)

        assertTrue(message!!, message.contains("室外"))
    }

    @Test
    fun `成功时没有失败文案可显示`() {
        assertNull(locationFailureMessage(LocationResult.Success(point()), granted))
        assertNull(locationFailureMessage(LocationResult.Success(point()), permanentlyDenied))
    }

    @Test
    fun `无权限且还能申请时只说要权限`() {
        // 此时界面会先把说明框推出去，这一句是用户点了「暂不」之后才看到的
        val message = locationFailureMessage(LocationResult.NoPermission, canAsk)

        assertTrue(message!!, message.contains("权限"))
        assertFalse(message, message.contains("系统设置"))
        assertFalse(message, message.contains("关闭"))
    }

    @Test
    fun `永久拒绝的文案指向系统设置`() {
        // 系统已经不再允许弹框，再说「需要定位权限」等于让用户对着一个点不动的按钮发呆
        val message = locationFailureMessage(LocationResult.NoPermission, permanentlyDenied)

        assertTrue(message!!, message.contains("系统设置"))
    }

    @Test
    fun `定位服务关闭时既不提权限也不提室外`() {
        // 服务被关掉是用户在系统里拨一个开关就能解决的事，跟权限、跟天空都无关
        val message = locationFailureMessage(LocationResult.ServicesDisabled, canAsk)

        assertTrue(message!!, message.contains("定位服务"))
        assertFalse(message, message.contains("权限"))
        assertFalse(message, message.contains("室外"))
    }

    // —— offersSettings ——

    @Test
    fun `只有永久拒绝才提供去设置的入口`() {
        assertTrue(offersSettings(LocationResult.NoPermission, permanentlyDenied))

        assertFalse(offersSettings(LocationResult.NoPermission, canAsk))
        assertFalse(offersSettings(LocationResult.Timeout, permanentlyDenied))
        assertFalse(offersSettings(LocationResult.ServicesDisabled, permanentlyDenied))
        assertFalse(offersSettings(LocationResult.Success(point()), permanentlyDenied))
    }

    @Test
    fun `超时不提供去设置的入口`() {
        // 超时的解法是重试或换个位置，把它变成一次跳转是把重试机会拿走
        assertFalse(offersSettings(LocationResult.Timeout, granted))
    }
}
