package com.spectrafilm.app

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** [AlbumsViewModel] over a fake store: edits persist in order, Undo, and a failed read. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AlbumsViewModelTest {
    private class FakeStore(
        var saved: List<Album> = emptyList(),
        val failLoad: Boolean = false,
        var failSave: Boolean = false,
    ) : AlbumStore {
        var saves = 0
        override fun load(): List<Album> {
            if (failLoad) throw java.io.IOException("busy")
            return saved
        }
        override fun save(albums: List<Album>) {
            saves++
            if (failSave) throw java.io.IOException("no space")
            saved = albums
        }
    }

    private fun item(n: Int) = AlbumItem("content://doc/$n", "f$n.jpg", "image/jpeg", n.toLong(), 1L, "content://tree/a")

    private var ids = 0
    private fun vm(store: AlbumStore) =
        AlbumsViewModel(store, Dispatchers.Unconfined, now = { 7L }, newId = { "id${ids++}" })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun createAddAndRenamePersist() {
        val store = FakeStore()
        val model = vm(store); idle()
        var created: Pair<String?, Int>? = null
        model.create("  Trip ", listOf(item(1))) { newId, count -> created = newId to count }; idle()
        val id = created!!.first!!
        assertEquals(1, created!!.second)
        var added = -1
        model.add(id, listOf(item(1), item(2))) { added = it }; idle()
        model.rename(id, "Roll 3"); idle()
        assertEquals(1, added)
        assertEquals(listOf(Album(id, "Roll 3", 7L, listOf(item(1), item(2)))), store.saved)
        assertEquals(store.saved, model.albums.value)
    }

    @Test fun deleteThenUndoRestoresTheAlbumInPlace() {
        val store = FakeStore(listOf(Album("a", "A", 1L), Album("b", "B", 2L, listOf(item(1))), Album("c", "C", 3L)))
        val model = vm(store); idle()
        val undo = model.delete("b"); idle()
        assertEquals(listOf("a", "c"), store.saved.map { it.id })
        // Until Undo is taken or lapses, the grant sweep still keeps the deleted album's folder.
        assertTrue("content://tree/a" in AlbumUndoHold.uris())
        model.undoDelete(undo!!.first, undo.second); idle()
        assertEquals(listOf("a", "b", "c"), store.saved.map { it.id })
        assertTrue(AlbumUndoHold.uris().isEmpty())
        val again = model.delete("b"); idle()
        model.undoExpired(again!!.first)
        assertTrue(AlbumUndoHold.uris().isEmpty())
        assertNull(model.delete("missing"))
    }

    @Test fun createReportsWhatTheAlbumBoundsAccepted() {
        val store = FakeStore()
        val model = vm(store); idle()
        val many = (0 until AlbumOps.MAX_ITEMS + 5).map { item(it) }
        var created: Pair<String?, Int>? = null
        model.create("Big", many) { newId, count -> created = newId to count }; idle()
        assertEquals(AlbumOps.MAX_ITEMS, created!!.second)
        assertEquals(AlbumOps.MAX_ITEMS, store.saved.single().items.size)
    }

    @Test fun aQueuedEditIsSavedAfterTheLibraryIsLeft() {
        val lock = Mutex()
        val writes = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = FakeStore()
        val owner = ViewModelStore()
        val model = ViewModelProvider(
            owner,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AlbumsViewModel(store, Dispatchers.Unconfined, newId = { "x" }, writes = writes, lock = lock) as T
            },
        )[AlbumsViewModel::class.java]
        idle() // the first read lands
        assertTrue(lock.tryLock()) // an earlier save still running
        model.create("Kept")
        owner.clear() // the user leaves the Library
        lock.unlock()
        idle()
        assertEquals(listOf("Kept"), store.saved.map { it.name })
    }

    @Test fun removeSelectedDropsThoseFramesAndClearsTheSelection() {
        val store = FakeStore(listOf(Album("a", "A", 1L, listOf(item(1), item(2), item(3)))))
        val model = vm(store); idle()
        model.toggleSelected("content://doc/1")
        model.toggleSelected("content://doc/3")
        assertEquals(2, model.removeSelected("a")); idle()
        assertEquals(listOf(item(2)), store.saved[0].items)
        assertTrue(model.selection.value.isEmpty())
    }

    @Test fun aFailedSaveIsUndoneOnScreenAndReported() {
        val store = FakeStore(listOf(Album("a", "A", 1L, listOf(item(1)))))
        val model = vm(store); idle()
        store.failSave = true
        var added: Int? = -1
        model.add("a", listOf(item(2))) { added = it }; idle()
        assertNull(added)
        assertEquals(listOf(item(1)), model.albums.value.single().items)
        var created: String? = "unset"
        model.create("New", listOf(item(3))) { newId, _ -> created = newId }; idle()
        assertNull(created)
        assertEquals(listOf("a"), model.albums.value.map { it.id })
    }

    @Test fun aFailedReadNeverOverwritesTheFile() {
        val store = FakeStore(listOf(Album("a", "A", 1L)), failLoad = true)
        val model = vm(store); idle()
        var created: String? = "unset"
        model.create("New") { newId, _ -> created = newId }; idle()
        assertNull(created)
        assertEquals(0, store.saves)
        assertTrue(model.albums.value.isEmpty())
    }
}
