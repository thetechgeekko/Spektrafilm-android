package com.spectrafilm.app

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * State transitions of [LibraryViewModel] (#280): folder choice, listing, failure, forgetting the
 * folder, and dropping a listing that finished after the folder changed. The store and lister
 * are fakes; the IO dispatcher is Unconfined so every step lands before the assertion.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibraryViewModelTest {
    private class FakeStore(initial: String? = null) : LibraryFolderStore {
        val log = mutableListOf<String>()
        var grantGranted = true
        override var treeUri: String? = initial
            set(value) { log += "set:$value"; field = value }

        override fun takeGrant(treeUri: String): Boolean {
            log += "grant:$treeUri:stored=${this.treeUri}"
            return grantGranted
        }

        override fun releaseUnusedGrants() { log += "release" }
    }

    private fun entry(name: String, mime: String?, modified: Long) =
        LibraryEntry("content://doc/$name", name, mime, modified, sizeBytes = 1L)

    private fun vm(store: LibraryFolderStore, lister: LibraryLister) =
        LibraryViewModel(store, lister, Dispatchers.Unconfined)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun noFolderMeansAnEmptyStateAndNoListing() {
        var listed = 0
        val model = vm(FakeStore()) { listed++; error("must not list") }
        model.refresh(); idle()
        assertEquals(LibraryUiState(), model.state.value)
        assertEquals(0, listed)
    }

    @Test fun refreshListsOnlyOpenableFramesNewestFirst() {
        val rows = listOf(
            entry("old.jpg", "image/jpeg", 10),
            entry("new.dng", "image/x-adobe-dng", 30),
            entry("clip.mp4", "video/mp4", 40),
            entry("notes.xmp", "application/octet-stream", 50),
            entry("sub", "vnd.android.document/directory", 60),
        )
        val model = vm(FakeStore("tree://a")) { LibraryListing("Roll 1", rows) }
        model.refresh(); idle()
        val state = model.state.value
        assertEquals("tree://a", state.treeUri)
        assertEquals("Roll 1", state.folderName)
        assertFalse(state.loading)
        assertFalse(state.unreadable)
        assertEquals(listOf("new.dng", "old.jpg"), state.entries.map { it.name })
    }

    @Test fun unreadableFolderIsReportedAndKeepsItsName() {
        var fail = false
        val model = vm(FakeStore("tree://a")) {
            if (fail) throw SecurityException("grant revoked")
            LibraryListing("Roll 1", listOf(entry("a.jpg", "image/jpeg", 1)))
        }
        model.refresh(); idle()
        fail = true
        model.refresh(); idle()
        val state = model.state.value
        assertTrue(state.unreadable)
        assertFalse(state.loading)
        assertEquals("Roll 1", state.folderName)
        assertTrue(state.entries.isEmpty())
    }

    @Test fun choosingAFolderStoresItBeforeTakingTheGrantAndReleasesTheOldOne() {
        val store = FakeStore("tree://old")
        val model = vm(store) { LibraryListing("New", emptyList()) }
        model.chooseFolder("tree://new"); idle()
        assertEquals("tree://new", store.treeUri)
        // The folder is recorded first, so a concurrent source restore counts the grant as ours.
        assertEquals(listOf("set:tree://new", "grant:tree://new:stored=tree://new", "release"), store.log)
        assertEquals("New", model.state.value.folderName)
    }

    @Test fun choosingTheSameFolderAgainReleasesNothing() {
        val store = FakeStore("tree://a")
        val model = vm(store) { LibraryListing("A", emptyList()) }
        model.chooseFolder("tree://a"); idle()
        assertFalse("release" in store.log)
    }

    @Test fun aRefusedGrantStillShowsTheFolderForThisSession() {
        val store = FakeStore().apply { grantGranted = false }
        val model = vm(store) { LibraryListing("A", listOf(entry("a.jpg", "image/jpeg", 1))) }
        model.chooseFolder("tree://a"); idle()
        assertEquals("tree://a", model.state.value.treeUri)
        assertEquals(listOf("a.jpg"), model.state.value.entries.map { it.name })
    }

    @Test fun forgettingTheFolderClearsStoreAndStateAndReleasesGrants() {
        val store = FakeStore("tree://a")
        val model = vm(store) { LibraryListing("A", listOf(entry("a.jpg", "image/jpeg", 1))) }
        model.refresh(); idle()
        model.forgetFolder(); idle()
        assertNull(store.treeUri)
        assertTrue("release" in store.log)
        assertEquals(LibraryUiState(), model.state.value)
    }

    @Test fun forgettingWithNoFolderDoesNothing() {
        val store = FakeStore()
        val model = vm(store) { error("must not list") }
        model.forgetFolder(); idle()
        assertTrue(store.log.isEmpty())
    }

    @Test fun aListingThatFinishesAfterTheFolderChangedIsDropped() {
        val store = FakeStore("tree://a")
        val model = vm(store) { tree ->
            // The folder changes while this listing is still running.
            store.treeUri = "tree://b"
            LibraryListing("Stale $tree", listOf(entry("a.jpg", "image/jpeg", 1)))
        }
        model.refresh(); idle()
        val state = model.state.value
        assertTrue(state.entries.isEmpty())
        assertEquals(null, state.folderName)
    }

    private fun pasteVm(
        rows: List<LibraryEntry>,
        paste: suspend (String, List<LookSync.Target>) -> LookSync.Result,
    ) = LibraryViewModel(FakeStore("tree://a"), { LibraryListing("A", rows) }, Dispatchers.Unconfined, { false }, paste)

    @Test fun longPressSelectionTogglesAndSelectAllTakesEveryFrame() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2))
        val model = pasteVm(rows) { _, _ -> error("no paste") }
        model.refresh(); idle()
        model.toggleSelected("content://doc/a.jpg")
        assertEquals(setOf("content://doc/a.jpg"), model.selection.value)
        model.toggleSelected("content://doc/a.jpg")
        assertTrue(model.selection.value.isEmpty())
        model.selectAll()
        assertEquals(2, model.selection.value.size)
        model.clearSelection()
        assertTrue(model.selection.value.isEmpty())
    }

    @Test fun pasteSendsTheSelectionMinusTheSourceAndEndsSelecting() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2))
        var sent: Pair<String, List<LookSync.Target>>? = null
        val model = pasteVm(rows) { source, targets ->
            sent = source to targets
            LookSync.Result(applied = targets.size, skipped = 0)
        }
        model.refresh(); idle()
        model.selectAll()
        model.pasteLookFrom("content://doc/a.jpg"); idle()
        assertEquals("content://doc/a.jpg", sent!!.first)
        assertEquals(listOf("b.jpg"), sent!!.second.map { it.name })
        assertTrue(model.selection.value.isEmpty())
        assertEquals(LibraryPasteState.Done(LookSync.Result(1, 0)), model.paste.value)
        model.pasteShown()
        assertEquals(LibraryPasteState.Idle, model.paste.value)
    }

    @Test fun pasteWithNoOpenPhotoKeepsTheSelectionAndSaysWhy() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1))
        val model = pasteVm(rows) { _, _ -> error("must not paste") }
        model.refresh(); idle()
        model.selectAll()
        model.pasteLookFrom(null); idle()
        assertEquals(1, model.selection.value.size)
        assertEquals(LibraryPasteState.Done(LookSync.Result(0, 0, noSource = true)), model.paste.value)
    }

    @Test fun aFrameThatLeftTheFolderLeavesTheSelection() {
        var rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2))
        val model = LibraryViewModel(FakeStore("tree://a"), { LibraryListing("A", rows) }, Dispatchers.Unconfined)
        model.refresh(); idle()
        model.selectAll()
        rows = rows.take(1)
        model.refresh(); idle()
        assertEquals(setOf("content://doc/a.jpg"), model.selection.value)
    }

    private class FakeSidecars(var writable: Boolean) : LibrarySidecarPort {
        val imports = mutableListOf<List<String>>()
        val saves = mutableListOf<List<String>>()
        override fun importMissing(frames: List<LibraryEntry>, rows: List<LibraryEntry>): Int {
            imports += rows.map { it.name }
            return 0
        }
        override suspend fun save(treeUri: String, frames: List<LibraryEntry>, rows: List<LibraryEntry>): LibrarySidecars.SaveResult {
            if (!writable) return LibrarySidecars.SaveResult(0, 0, needsWriteAccess = true)
            saves += frames.map { it.name }
            return LibrarySidecars.SaveResult(frames.size, 0)
        }
    }

    private fun sidecarVm(store: LibraryFolderStore, rows: List<LibraryEntry>, sidecars: LibrarySidecarPort, edited: Set<String>) =
        LibraryViewModel(
            store,
            { LibraryListing("A", rows) },
            Dispatchers.Unconfined,
            { it in edited },
            { _, _ -> error("no paste") },
            sidecars,
        )

    @Test fun refreshOffersTheWholeListingForSidecarImport() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("a.jpg.spektra.json", "application/json", 2))
        val sidecars = FakeSidecars(writable = true)
        val model = sidecarVm(FakeStore("tree://a"), rows, sidecars, emptySet())
        model.refresh(); idle()
        assertEquals(listOf(listOf("a.jpg", "a.jpg.spektra.json")), sidecars.imports)
        assertEquals(listOf("a.jpg"), model.state.value.entries.map { it.name })
    }

    @Test fun saveBesidePhotosTakesEditedFramesOrTheEditedSelection() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2), entry("c.jpg", "image/jpeg", 3))
        val sidecars = FakeSidecars(writable = true)
        val edited = setOf("content://doc/a.jpg", "content://doc/b.jpg")
        val model = sidecarVm(FakeStore("tree://a"), rows, sidecars, edited)
        model.refresh(); idle()
        model.saveEditsBesidePhotos(); idle()
        assertEquals(setOf("a.jpg", "b.jpg"), sidecars.saves.last().toSet())
        model.toggleSelected("content://doc/b.jpg")
        model.toggleSelected("content://doc/c.jpg")
        model.saveEditsBesidePhotos(); idle()
        assertEquals(listOf("b.jpg"), sidecars.saves.last())
    }

    @Test fun withoutWriteAccessChoosingTheFolderAgainFinishesTheSave() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1))
        val sidecars = FakeSidecars(writable = false)
        val store = FakeStore("tree://a")
        val model = sidecarVm(store, rows, sidecars, setOf("content://doc/a.jpg"))
        model.refresh(); idle()
        model.saveEditsBesidePhotos(); idle()
        assertEquals(
            LibrarySidecarState.Done(LibrarySidecars.SaveResult(0, 0, needsWriteAccess = true)),
            model.sidecarSave.value,
        )
        model.sidecarSaveShown()
        sidecars.writable = true
        model.chooseFolder("tree://a"); idle()
        assertEquals(listOf("a.jpg"), sidecars.saves.single())
    }

    @Test fun aSaveWaitingWhileTheFolderChangesWritesNothing() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2))
        val sidecars = FakeSidecars(writable = true)
        val store = FakeStore("tree://a")
        val pasted = CompletableDeferred<LookSync.Result>()
        val model = LibraryViewModel(
            store,
            { LibraryListing("A", rows) },
            Dispatchers.Unconfined,
            { true },
            { _, _ -> pasted.await() },
            sidecars,
        )
        model.refresh(); idle()
        model.toggleSelected("content://doc/b.jpg")
        model.pasteLookFrom("content://doc/a.jpg"); idle()
        // The save waits for the paste; meanwhile the user moves to another folder.
        model.saveEditsBesidePhotos(); idle()
        model.chooseFolder("tree://b"); idle()
        pasted.complete(LookSync.Result(applied = 1, skipped = 0)); idle()
        assertTrue(sidecars.saves.isEmpty())
        assertEquals(LibrarySidecarState.Idle, model.sidecarSave.value)
    }

    @Test fun aSaveFromALaterEntryWaitsForAnEarlierEntrysPasteAndListsAgain() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2))
        val sidecars = FakeSidecars(writable = true)
        val edited = mutableSetOf("content://doc/a.jpg")
        val pasted = CompletableDeferred<LookSync.Result>()
        val shared = Mutex()
        fun entryVm() = LibraryViewModel(
            FakeStore("tree://a"),
            { LibraryListing("A", rows) },
            Dispatchers.Unconfined,
            { it in edited },
            { _, _ -> pasted.await() },
            sidecars,
            writeLock = shared,
        )
        // The first Library entry starts a paste onto b.jpg, then the user leaves.
        val first = entryVm()
        first.refresh(); idle()
        first.toggleSelected("content://doc/b.jpg")
        first.pasteLookFrom("content://doc/a.jpg"); idle()
        // A new entry lists the folder before b.jpg is edited and asks to save.
        val second = entryVm()
        second.refresh(); idle()
        second.saveEditsBesidePhotos(); idle()
        assertTrue(sidecars.saves.isEmpty())
        // The paste lands: the save lists again and includes b.jpg.
        edited += "content://doc/b.jpg"
        pasted.complete(LookSync.Result(applied = 1, skipped = 0)); idle()
        assertEquals(setOf("a.jpg", "b.jpg"), sidecars.saves.single().toSet())
    }

    @Test fun aSaveFromALaterEntryWaitsForAnEarlierSaveAndListsItsSidecars() {
        val rows = mutableListOf(entry("a.jpg", "image/jpeg", 1))
        val released = CompletableDeferred<Unit>()
        val rowsSeen = mutableListOf<List<String>>()
        val sidecars = object : LibrarySidecarPort {
            override fun importMissing(frames: List<LibraryEntry>, rows: List<LibraryEntry>) = 0
            override suspend fun save(treeUri: String, frames: List<LibraryEntry>, rows: List<LibraryEntry>): LibrarySidecars.SaveResult {
                rowsSeen += rows.map { it.name }
                if (rowsSeen.size == 1) released.await()
                return LibrarySidecars.SaveResult(frames.size, 0)
            }
        }
        val shared = Mutex()
        fun entryVm() = LibraryViewModel(
            FakeStore("tree://a"),
            { LibraryListing("A", rows.toList()) },
            Dispatchers.Unconfined,
            { true },
            { _, _ -> error("no paste") },
            sidecars,
            writeLock = shared,
        )
        val first = entryVm()
        first.refresh(); idle()
        first.saveEditsBesidePhotos(); idle()
        // The user leaves and saves again from a new entry before the first save is done.
        val second = entryVm()
        second.refresh(); idle()
        second.saveEditsBesidePhotos(); idle()
        assertEquals(1, rowsSeen.size)
        // The first save writes a.jpg's sidecar; the second must see it, not create another.
        rows += entry("a.jpg.spektra.json", "application/json", 2)
        released.complete(Unit); idle()
        assertEquals(listOf(listOf("a.jpg"), listOf("a.jpg", "a.jpg.spektra.json")), rowsSeen)
    }

    @Test fun aSaveAfterAnEarlierEntrysPasteFinishedStillListsAgain() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 1), entry("b.jpg", "image/jpeg", 2))
        val sidecars = FakeSidecars(writable = true)
        val edited = mutableSetOf("content://doc/a.jpg")
        val pasted = CompletableDeferred<LookSync.Result>()
        val shared = Mutex()
        fun entryVm() = LibraryViewModel(
            FakeStore("tree://a"),
            { LibraryListing("A", rows) },
            Dispatchers.Unconfined,
            { it in edited },
            { _, _ -> pasted.await() },
            sidecars,
            writeLock = shared,
        )
        val first = entryVm()
        first.refresh(); idle()
        first.toggleSelected("content://doc/b.jpg")
        first.pasteLookFrom("content://doc/a.jpg"); idle()
        // A new entry lists the folder before the paste lands; the save comes after it.
        val second = entryVm()
        second.refresh(); idle()
        edited += "content://doc/b.jpg"
        pasted.complete(LookSync.Result(applied = 1, skipped = 0)); idle()
        second.saveEditsBesidePhotos(); idle()
        assertEquals(setOf("a.jpg", "b.jpg"), sidecars.saves.single().toSet())
    }
    private class MemoryQueryStore(var saved: LibraryQuery = LibraryQuery()) : LibraryQueryStore {
        var saves = 0
        override fun load() = saved
        override fun save(query: LibraryQuery) { saves++; saved = query }
    }

    @Test fun queryIsRestoredAndOnlySortAndFilterChangesAreSaved() {
        val store = MemoryQueryStore(LibraryQuery(sort = LibrarySort.NAME, descending = false))
        val model = LibraryViewModel(FakeStore(), { LibraryListing(null, emptyList()) }, Dispatchers.Unconfined, queryStore = store)
        assertEquals(LibrarySort.NAME, model.query.value.sort)
        model.setQuery(model.query.value.copy(text = "beach"))
        assertEquals(0, store.saves)
        model.setQuery(model.query.value.copy(filter = LibraryFilter.RAW))
        assertEquals(1, store.saves)
        assertEquals(LibraryFilter.RAW, store.saved.filter)
    }

    @Test fun narrowingDropsHiddenFramesFromSelectionAndSelectAllTakesOnlyShownOnes() {
        val rows = listOf(entry("a.jpg", "image/jpeg", 2), entry("b.dng", "image/x-adobe-dng", 1))
        val model = vm(FakeStore("tree://a")) { LibraryListing("Roll", rows) }
        model.refresh(); idle()
        model.selectAll()
        assertEquals(2, model.selection.value.size)
        model.setQuery(LibraryQuery(filter = LibraryFilter.RAW))
        assertEquals(setOf("content://doc/b.dng"), model.selection.value)
        assertEquals(listOf("b.dng"), model.visibleEntries().map { it.name })
        model.clearSelection()
        model.selectAll()
        assertEquals(setOf("content://doc/b.dng"), model.selection.value)
    }

    @Test fun refreshDropsSelectedFramesTheFilterNowHides() {
        var editedNow = false
        val rows = listOf(entry("a.jpg", "image/jpeg", 2), entry("b.jpg", "image/jpeg", 1))
        val model = LibraryViewModel(
            FakeStore("tree://a"),
            { LibraryListing("Roll", rows) },
            Dispatchers.Unconfined,
            hasEdit = { uri -> editedNow && uri.endsWith("a.jpg") },
        )
        model.setQuery(LibraryQuery(filter = LibraryFilter.NOT_EDITED))
        model.refresh(); idle()
        model.selectAll()
        assertEquals(2, model.selection.value.size)
        editedNow = true
        model.refresh(); idle()
        assertEquals(setOf("content://doc/b.jpg"), model.selection.value)
    }
}
