package com.magnate.compass.ui.nav

/**
 * 导航路由（design-gui.md §2）。
 *
 * 不使用底部导航栏：主页是唯一一级页面，其余为二级。底部导航会永久占用 80dp 高度，
 * 挤压罗盘空间——罗盘是核心，不该为不常用的管理页让位。
 */
object Routes {
    const val COMPASS = "compass"
    const val RECORDS = "records"
    const val SEARCH = "search"
    const val SCENES = "scenes"
    const val TAGS = "tags"

    const val RECORD_ID_ARG = "recordId"
    const val SCENE_ID_ARG = "sceneId"

    const val RECORD_DETAIL = "record/{$RECORD_ID_ARG}"
    const val SCENE_DETAIL = "scene/{$SCENE_ID_ARG}"

    fun recordDetail(recordId: Long): String = "record/$recordId"

    fun sceneDetail(sceneId: Long): String = "scene/$sceneId"
}
