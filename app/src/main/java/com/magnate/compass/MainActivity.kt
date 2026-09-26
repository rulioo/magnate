package com.magnate.compass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.magnate.compass.ui.MagnateAppRoot
import com.magnate.compass.ui.theme.MagnateTheme

/**
 * 唯一的 Activity。
 *
 * 单 Activity + Compose 导航，不注册 `screenOrientation`——横屏时罗盘页自己
 * 切成左右分栏（design-gui.md §14），靠 `configChanges` 声明避免 Activity 重建
 * （见 AndroidManifest），这样横竖屏切换时传感器订阅与导航栈都不会丢。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MagnateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    MagnateAppRoot()
                }
            }
        }
    }
}
