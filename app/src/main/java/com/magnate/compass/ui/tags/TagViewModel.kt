package com.magnate.compass.ui.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.magnate.compass.data.TagRepository
import com.magnate.compass.data.TagWithCount
import com.magnate.compass.data.TagWriteResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 标签管理（design-gui.md §10）。
 *
 * 与场景管理平行但**概念不同**：场景回答「在哪儿测的」，标签回答「怎么分类的」。
 * 页面上两处提示语各自回答该页最容易被误解的那个问题——
 * 场景页说「固定模式下场景内记录共用此坐标」，标签页说「删除标签不会删除记录」。
 */
class TagViewModel(private val tagRepository: TagRepository) : ViewModel() {

    val tags: StateFlow<List<TagWithCount>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 新建。重名返回 [TagWriteResult.Duplicate]，UI 据此提示而不是静默忽略。 */
    fun create(name: String, onResult: (TagWriteResult) -> Unit) {
        viewModelScope.launch { onResult(tagRepository.createTag(name)) }
    }

    fun rename(id: Long, newName: String, onResult: (TagWriteResult) -> Unit) {
        viewModelScope.launch { onResult(tagRepository.renameTag(id, newName)) }
    }

    /** 改色即时生效——这是低风险操作，不需要确认。 */
    fun setColor(id: Long, colorIndex: Int) {
        viewModelScope.launch { tagRepository.updateColor(id, colorIndex) }
    }

    /** 删除标签。关联的记录**不会被删除**，只是不再带有这个标签。 */
    fun delete(id: Long) {
        viewModelScope.launch { tagRepository.deleteTag(id) }
    }
}
