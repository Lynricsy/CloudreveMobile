package plus.ling.opencloudreve.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.KClass

private class ProbeViewModel : ViewModel() {
    var cleared = false

    override fun onCleared() {
        cleared = true
    }
}

private object ProbeFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: KClass<T>, extras: CreationExtras): T = when (modelClass) {
        ProbeViewModel::class -> ProbeViewModel() as T
        ScopedViewModelStores::class -> ScopedViewModelStores() as T
        else -> error("unexpected $modelClass")
    }
}

/** 详情窗格 / Tab 内弹窗的 ViewModel 作用域：关闭后再次以同一 key 打开得到新实例，而不是复用旧的 */
class ScopedViewModelStoresTest {
    private fun probe(owner: ViewModelStoreOwner): ProbeViewModel = ViewModelProvider.create(owner, ProbeFactory)[ProbeViewModel::class]

    @Test
    fun reopeningAfterClearCreatesFreshViewModel() {
        val stores = ScopedViewModelStores()
        val first = probe(stores.owner("share-edit:1@a:s1"))
        assertSame(first, probe(stores.owner("share-edit:1@a:s1")))

        stores.clear("share-edit:1@a:s1")
        assertTrue(first.cleared)
        assertNotSame(first, probe(stores.owner("share-edit:1@a:s1")))
    }

    @Test
    fun clearingOneScopeLeavesOthersAlive() {
        val stores = ScopedViewModelStores()
        val a = probe(stores.owner("info:1@a:cloudreve://my/a.txt"))
        val b = probe(stores.owner("info:2@b:cloudreve://my/a.txt"))
        assertNotSame(a, b)
        stores.clear("info:1@a:cloudreve://my/a.txt")
        assertTrue(a.cleared)
        assertFalse(b.cleared)
    }

    @Test
    fun clearingTheParentDestinationClearsEveryScope() {
        val parent = ViewModelStore()
        val parentOwner = object : ViewModelStoreOwner {
            override val viewModelStore = parent
        }
        val stores = ViewModelProvider.create(parentOwner, ProbeFactory)[ScopedViewModelStores::class]
        val a = probe(stores.owner("x"))
        parent.clear()
        assertTrue(a.cleared)
    }
}
