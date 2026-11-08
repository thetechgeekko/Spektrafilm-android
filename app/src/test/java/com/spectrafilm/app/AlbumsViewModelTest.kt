package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.Dispatchers
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
    private class FakeStore(var saved: List<Album> = emptyList(), val failLoad: Boolean = false) : AlbumStore {
        var saves = 0
        override fun load(): List<Album> {
            if (failLoad) throw java.io.IOException("busy")
            return saved
        }
        override fun save(albums: List<Album>) { saves++; saved = albums }
    }

    private fun item(n: Int) = AlbumItem("content://doc/$n", "f$n.jpg", "image/jpeg", n.toLong(), 1L, "content://tree/a")

    private var ids = 0
    private fun vm(store: AlbumStore) =
        AlbumsViewModel(store, Dispatchers.Unconfined, now = { 7L }, newId = { "id${ids++}" })

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun createAddAndRenamePersist() {
        val store = FakeStore()
        val model = vm(store); idle()
        val id = model.create("  Trip ", listOf(item(1))); idle()
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
        model.undoDelete(undo!!.first, undo.second); idle()
        assertEquals(listOf("a", "b", "c"), store.saved.map { it.id })
        assertNull(model.delete("missing"))
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

    @Test fun aFailedReadNeverOverwritesTheFile() {
        val store = FakeStore(listOf(Album("a", "A", 1L)), failLoad = true)
        val model = vm(store); idle()
        model.create("New"); idle()
        assertEquals(0, store.saves)
        assertTrue(model.albums.value.isEmpty())
    }
}
