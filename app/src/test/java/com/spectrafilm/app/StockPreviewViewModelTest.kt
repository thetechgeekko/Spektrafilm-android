package com.spectrafilm.app

import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.spectrafilm.engine.LinearImage
import com.spectrafilm.engine.SpektraParams
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executor
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * [StockPreviewViewModel] (#280) around its pure [StockPreviewQueue]: what the tiles show
 * while rendering, which stock renders first, when the photo copy is made, reused and closed,
 * and that a failure leaves a tile that can be asked for again. The renderer is a fake that
 * logs which stock it rendered; the background lane runs inline on the calling thread.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StockPreviewViewModelTest {
    private val inline = Executor { it.run() }.asCoroutineDispatcher()

    private class FakeRenderer : StockPreviewRenderer {
        val rendered = mutableListOf<String>()
        val failOnce = mutableSetOf<String>()
        override fun render(
            image: LinearImage,
            params: SpektraParams,
            toBitmap: (com.spectrafilm.engine.SimResult) -> android.graphics.Bitmap,
        ): ImageBitmap {
            val id = params.filmProfile
            if (failOnce.remove(id)) error("render of $id failed")
            // Build the tile before logging it, so a tile that could not be made never
            // looks like a successful render.
            val tile = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                .asImageBitmap()
            rendered += id
            return tile
        }
    }

    /** Counts the photo copies a session makes, and keeps them to check they were closed. */
    private class Sources(val returnsNull: Boolean = false) {
        val made = mutableListOf<LinearImage>()
        suspend fun make(): LinearImage? {
            if (returnsNull) return null
            val b = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.nativeOrder())
            return LinearImage(b, 1, 1).also { made += it }
        }
    }

    private fun session(key: Any, sources: Sources) = StockPreviewSession(
        key = key,
        source = { sources.make() },
        paramsFor = { SpektraParams(filmProfile = it, printProfile = "paper") },
        toBitmap = { error("the fake renderer never calls toBitmap") },
    )

    private fun isClosed(image: LinearImage) = runCatching { image.acquireDataLease().close() }.isFailure

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun requestsBeforeTheSheetOpensShowPlaceholdersAndRenderNothing() {
        val renderer = FakeRenderer()
        val model = StockPreviewViewModel(renderer, inline)
        model.request("a"); model.request("b"); idle()
        assertEquals(mapOf("b" to null, "a" to null), model.previews.value)
        assertTrue(renderer.rendered.isEmpty())
    }

    @Test fun openingRendersTheNewestRequestFirstFromOneCopyOfThePhoto() {
        val renderer = FakeRenderer()
        val sources = Sources()
        val model = StockPreviewViewModel(renderer, inline)
        model.request("a"); model.request("b"); model.request("c")
        model.open(session("look1", sources)); idle()
        assertEquals(listOf("c", "b", "a"), renderer.rendered)
        assertEquals(1, sources.made.size)
        val previews = model.previews.value
        assertEquals(setOf("a", "b", "c"), previews.keys)
        assertTrue(previews.values.all { it != null })
    }

    @Test fun aRenderedTileIsNotRenderedAgainWhenAskedTwice() {
        val renderer = FakeRenderer()
        val model = StockPreviewViewModel(renderer, inline)
        model.open(session("look1", Sources()))
        model.request("a"); idle()
        model.request("a"); idle()
        assertEquals(listOf("a"), renderer.rendered)
    }

    @Test fun reopeningWithTheSameLookKeepsTilesAndThePhotoCopy() {
        val renderer = FakeRenderer()
        val sources = Sources()
        val model = StockPreviewViewModel(renderer, inline)
        model.open(session("look1", sources))
        model.request("a"); idle()
        model.close()
        model.open(session("look1", sources)); idle()
        assertEquals(listOf("a"), renderer.rendered)
        assertEquals(1, sources.made.size)
        assertFalse(isClosed(sources.made[0]))
        assertNotNull(model.previews.value["a"])
    }

    @Test fun aNewLookRerendersFinishedTilesFromANewCopyAndClosesTheOldOne() {
        val renderer = FakeRenderer()
        val sources = Sources()
        val model = StockPreviewViewModel(renderer, inline)
        model.open(session("look1", sources))
        model.request("a"); idle()
        model.open(session("look2", sources)); idle()
        assertEquals(listOf("a", "a"), renderer.rendered)
        assertEquals(2, sources.made.size)
        assertTrue(isClosed(sources.made[0]))
        assertFalse(isClosed(sources.made[1]))
        assertNotNull(model.previews.value["a"])
    }

    @Test fun nothingRendersWhileTheSheetIsClosed() {
        val renderer = FakeRenderer()
        val model = StockPreviewViewModel(renderer, inline)
        model.open(session("look1", Sources()))
        model.close()
        model.request("a"); idle()
        assertTrue(renderer.rendered.isEmpty())
        assertTrue("a" in model.previews.value)
        assertNull(model.previews.value["a"])
        model.open(session("look1", Sources())); idle()
        assertEquals(listOf("a"), renderer.rendered)
    }

    @Test fun oneOwnerClosingDoesNotStopAnotherOwnersTiles() {
        // The Film & look strip and the browser sheet share the model: dismissing the sheet
        // must leave the strip rendering.
        val renderer = FakeRenderer()
        val model = StockPreviewViewModel(renderer, inline)
        val strip = Any()
        val sheet = Any()
        model.open(session("look1", Sources()), strip)
        model.open(session("look1", Sources()), sheet)
        model.close(sheet)
        model.request("a"); idle()
        assertEquals(listOf("a"), renderer.rendered)
        model.close(strip)
        model.request("b"); idle()
        assertEquals(listOf("a"), renderer.rendered)
    }

    @Test fun aFailedTileDropsItsPlaceholderAndCanBeAskedForAgain() {
        val renderer = FakeRenderer().apply { failOnce += "a" }
        val model = StockPreviewViewModel(renderer, inline)
        model.open(session("look1", Sources()))
        model.request("a"); idle()
        assertFalse("a" in model.previews.value)
        model.request("a"); idle()
        assertEquals(listOf("a"), renderer.rendered)
        assertNotNull(model.previews.value["a"])
    }

    @Test fun noPhotoCopyMeansNoRenderAndNoStuckPlaceholder() {
        val renderer = FakeRenderer()
        val model = StockPreviewViewModel(renderer, inline)
        model.open(session("look1", Sources(returnsNull = true)))
        model.request("a"); idle()
        assertTrue(renderer.rendered.isEmpty())
        assertFalse("a" in model.previews.value)
    }

    @Test fun clearingTheViewModelClosesThePhotoCopy() {
        val sources = Sources()
        val model = StockPreviewViewModel(FakeRenderer(), inline)
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
        })
        assertTrue(provider[StockPreviewViewModel::class.java] === model)
        model.open(session("look1", sources))
        model.request("a"); idle()
        store.clear()
        assertTrue(isClosed(sources.made.single()))
    }
}
