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

/** The Gallery tab's state over a fake MediaStore listing and a switchable access level. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GalleryViewModelTest {
    private fun entry(id: Int, name: String = "IMG_$id.jpg", mime: String? = "image/jpeg") =
        LibraryEntry("content://media/external/images/media/$id", name, mime, id.toLong(), 1L)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun noAccessListsNothing() {
        var listed = 0
        val model = GalleryViewModel({ listed++; error("must not list") }, { GalleryAccess.NONE }, Dispatchers.Unconfined)
        model.refresh(); idle()
        assertEquals(GalleryUiState(access = GalleryAccess.NONE), model.state.value)
        assertEquals(0, listed)
    }

    @Test fun listingCarriesEditMarksAndShapes() {
        val rows = listOf(entry(1), entry(2))
        val model = GalleryViewModel(
            { GalleryListing(rows, mapOf(rows[0].documentUri to 0.75f)) },
            { GalleryAccess.PARTIAL },
            Dispatchers.Unconfined,
            hasEdit = { it.endsWith("/2") },
        )
        model.refresh(); idle()
        val state = model.state.value
        assertEquals(GalleryAccess.PARTIAL, state.access)
        assertEquals(listOf(false, true), state.entries.map { it.edited })
        assertEquals(0.75f, model.aspects.value[rows[0].documentUri])
    }

    @Test fun losingAccessClearsTheGridAndTheSelection() {
        var access = GalleryAccess.FULL
        val model = GalleryViewModel({ GalleryListing(listOf(entry(1)), emptyMap()) }, { access }, Dispatchers.Unconfined)
        model.refresh(); idle()
        model.toggleSelected(entry(1).documentUri)
        access = GalleryAccess.NONE
        model.refresh(); idle()
        assertTrue(model.state.value.entries.isEmpty())
        assertTrue(model.selection.value.isEmpty())
    }

    @Test fun aFailedListingSaysSo() {
        val model = GalleryViewModel({ throw SecurityException("revoked") }, { GalleryAccess.FULL }, Dispatchers.Unconfined)
        model.refresh(); idle()
        assertTrue(model.state.value.failed)
    }

    @Test fun shapeFollowsMediaStoreRotation() {
        assertEquals(1.5f, PhoneGallery.aspectOf(6000, 4000, 0)!!, 1e-6f)
        assertEquals(4000f / 6000f, PhoneGallery.aspectOf(6000, 4000, 90)!!, 1e-6f)
        assertEquals(1.5f, PhoneGallery.aspectOf(6000, 4000, 180)!!, 1e-6f)
        assertNull(PhoneGallery.aspectOf(0, 4000, 0))
    }
}
