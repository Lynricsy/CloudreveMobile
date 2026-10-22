package plus.ling.opencloudreve.ui.common

import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * 挂在父 [ViewModelStoreOwner]（所在导航目的地）上的一组可单独清理的子 [ViewModelStore]。
 * 父目的地销毁时全部清理；[clear] 清理单个 key 及其中的全部 ViewModel。
 */
class ScopedViewModelStores : ViewModel() {
    private val stores = HashMap<String, ViewModelStore>()

    /** [key] 对应的 owner；该 key 已清理过则得到新的空 store */
    fun owner(key: String): ViewModelStoreOwner {
        val store = stores.getOrPut(key) { ViewModelStore() }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = store
        }
    }

    fun clear(key: String) {
        stores.remove(key)?.clear()
    }

    override fun onCleared() {
        stores.values.forEach(ViewModelStore::clear)
        stores.clear()
    }
}

/**
 * 给不是导航目的地的界面（详情窗格、Tab 内弹窗）一个独立的 ViewModel 作用域：
 * [content] 内的 `koinViewModel()`/`viewModel()` 存在 [key] 对应的子 store 里，
 * 离开组合（关闭、切换账户/文件）时清理——配置变更（旋转等）重建 Activity 时保留。
 * 否则这些 ViewModel 会留在外层目的地的 store 里，下次以同一 key 打开时复用旧实例（旧账户、旧结果）。
 */
@Composable
fun ScopedViewModels(key: String, content: @Composable () -> Unit) {
    val stores: ScopedViewModelStores = viewModel(key = "scoped-view-model-stores")
    val owner = remember(stores, key) { stores.owner(key) }
    val activity = LocalActivity.current
    DisposableEffect(stores, key) {
        onDispose {
            if (activity?.isChangingConfigurations != true) stores.clear(key)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}
