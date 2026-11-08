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
        var quarantined = false
        override fun hasQuarantined() = quarantined
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
    private fun vm(
        store: AlbumStore,
        importer: AlbumImporter = AlbumImporter { _, _ -> null },
        prune: (Set<String>) -> Unit = {},
    ) = AlbumsViewModel(
        store,
        Dispatchers.Unconfined,
        now = { 7L },
        newId = { "id${ids++}" },
        importer = importer,
        pruneImports = prune,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun createAddAndRenamePersist() {
        val store = FakeStore()
        val model = vm(store); idle()
        var created: Pair<String?, Int>? = null
        model.create("  Trip ", listOf(item(1))) { newId, count -> created = newId to count }; idle()
        val id = created!!.first!!
        assertEquals(1, created!!.second)
        var added: Int? = -1
        model.add(id, listOf(item(1), item(2))) { added = it }; idle()
        model.rename(id, "Roll 3"); idle()
        assertEquals(1, added)
        assertEquals(listOf(Album(id, "Roll 3", 7L, listOf(item(1), item(2)))), store.saved)
        assertEquals(store.saved, model.albums.value)
    }

    @Test fun deleteThenUndoRestoresTheAlbumInPlace() {
        val store = FakeStore(listOf(Album("a", "A", 1L), Album("b", "B", 2L, listOf(item(1))), Album("c", "C", 3L)))
        val model = vm(store); idle()
        var undo: Pair<Album, Int>? = null
        model.delete("b") { album, index -> undo = album to index }; idle()
        assertEquals(listOf("a", "c"), store.saved.map { it.id })
        // Until Undo is taken or lapses, the grant sweep still keeps the deleted album's folder.
        assertTrue("content://tree/a" in AlbumUndoHold.uris())
        model.undoDelete(undo!!.first, undo!!.second); idle()
        assertEquals(listOf("a", "b", "c"), store.saved.map { it.id })
        assertTrue(AlbumUndoHold.uris().isEmpty())
        var again: Album? = null
        model.delete("b") { album, _ -> again = album }; idle()
        model.undoExpired(again!!)
        assertTrue(AlbumUndoHold.uris().isEmpty())
        var missing = false
        model.delete("missing") { _, _ -> missing = true }; idle()
        assertTrue(!missing)
    }

    @Test fun undoBringsBackTheAlbumAsTheQueuedEditsLeftIt() {
        val store = FakeStore(listOf(Album("b", "B", 2L, listOf(item(1), item(2)))))
        val lock = Mutex()
        val model = AlbumsViewModel(store, Dispatchers.Unconfined, now = { 7L }, newId = { "id${ids++}" }, sync = AlbumSync(lock))
        idle()
        // An earlier save still holds the lock while a photo is removed and the album deleted.
        kotlinx.coroutines.runBlocking { lock.lock() }
        model.toggleSelected("content://doc/1")
        model.removeSelected("b")
        var undo: Pair<Album, Int>? = null
        model.delete("b") { album, index -> undo = album to index }; idle()
        lock.unlock(); idle()
        assertEquals(listOf("content://doc/2"), undo!!.first.items.map { it.uri })
        model.undoDelete(undo!!.first, undo!!.second); idle()
        assertEquals(listOf("content://doc/2"), store.saved.single().items.map { it.uri })
    }

    @Test fun anAdditionWaitingToBeSavedKeepsItsFolderGrant() {
        val store = FakeStore(listOf(Album("a", "A", 1L)))
        val lock = Mutex()
        val model = AlbumsViewModel(store, Dispatchers.Unconfined, now = { 7L }, newId = { "id${ids++}" }, sync = AlbumSync(lock))
        idle()
        // Another edit holds the lock, so this one waits; a grant sweep now must keep folder B.
        kotlinx.coroutines.runBlocking { lock.lock() }
        val fromB = AlbumItem("content://doc/9", "f9.jpg", "image/jpeg", 9L, 1L, "content://tree/b")
        var added: Int? = -1
        model.add("a", listOf(fromB)) { added = it }; idle()
        model.create("New", listOf(fromB.copy(uri = "content://doc/10"))); idle()
        assertTrue("content://tree/b" in AlbumUndoHold.uris())
        lock.unlock(); idle()
        assertEquals(1, added)
        assertEquals(2, store.saved.size)
        assertTrue("content://tree/b" !in AlbumUndoHold.uris())
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
                    AlbumsViewModel(store, Dispatchers.Unconfined, newId = { "x" }, writes = writes, sync = AlbumSync(lock)) as T
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

    @Test fun twoVisitsEditTheSameAlbumsNotTheirOwnSnapshots() {
        val store = FakeStore(listOf(Album("a", "A", 1L)))
        val sync = AlbumSync()
        fun open() = AlbumsViewModel(store, Dispatchers.Unconfined, now = { 7L }, newId = { "id${ids++}" }, sync = sync)
        val first = open(); idle()
        val second = open(); idle() // the Library reopened while the first visit still works
        first.add("a", listOf(item(1))); idle()
        // The second visit shows the first one's save, and its own edit keeps it.
        assertEquals(listOf("content://doc/1"), second.albums.value.single().items.map { it.uri })
        second.rename("a", "Roll"); idle()
        assertEquals(listOf(Album("a", "Roll", 1L, listOf(item(1)))), store.saved)
    }

    @Test fun aQuarantinedAlbumsFileStopsTheCopyPrune() {
        val store = FakeStore().apply { quarantined = true }
        var pruned = false
        vm(store, prune = { pruned = true }); idle()
        assertTrue(!pruned)
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

    @Test fun importLandsInTheImportedAlbumAndCountsWhatFailed() {
        val store = FakeStore()
        val grants = mutableListOf<Boolean>()
        val model = vm(store, importer = { uri, keep ->
            grants += keep
            if (uri.endsWith("bad")) null else AlbumItem(uri, "x.jpg", "image/jpeg", 1L, 1L, null)
        }); idle()
        var result: Pair<Int?, Int>? = null
        model.import(listOf("content://a", "content://bad", "content://a"), keepGrants = true, albumName = "Imported") { added, failed ->
            result = added to failed
        }; idle()
        assertEquals(1 to 1, result)
        assertEquals(listOf(true, true), grants)
        assertEquals(AlbumOps.IMPORTED_ID, store.saved.single().id)
        assertEquals(listOf("content://a"), store.saved.single().items.map { it.uri })
        model.import(listOf("content://a"), keepGrants = false, albumName = "Imported") { added, failed ->
            result = added to failed
        }; idle()
        assertEquals(0 to 0, result)
    }

    @Test fun anImportSettlesItsCopiesOnlyAfterItsSave() {
        val store = FakeStore()
        val settled = ArrayList<String>()
        var savesWhenSettled = -1
        val importer = object : AlbumImporter {
            override fun import(uri: String, keepGrant: Boolean) =
                AlbumItem("$uri-copy", "x.jpg", "image/jpeg", 1L, 1L, null)
            override fun settle(itemUris: Collection<String>) {
                savesWhenSettled = store.saves
                settled += itemUris
            }
        }
        val model = vm(store, importer = importer); idle()
        model.import(listOf("content://a"), keepGrants = false, albumName = "Imported"); idle()
        assertEquals(listOf("content://a-copy"), settled)
        assertEquals(1, savesWhenSettled)
    }

    @Test fun importedCopiesArePrunedAgainstEveryAlbumAfterAGoodRead() {
        var kept: Set<String>? = null
        vm(FakeStore(listOf(Album("a", "A", 1L, listOf(item(1))))), prune = { kept = it }); idle()
        assertEquals(setOf("content://doc/1"), kept)
        kept = null
        vm(FakeStore(failLoad = true), prune = { kept = it }); idle()
        assertNull(kept)
    }
}
