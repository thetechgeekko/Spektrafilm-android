/*
 * Spektrafilm for Android — the editor's open placeholder, the swipe to the neighbouring photo
 * and the folder strip. GPLv3. Film modeling powered by spektrafilm.
 *
 * Moved verbatim out of EditorScreen (#273). Editor values that the effects read after launch
 * (the preview, preview-busy, rotation) are passed as their state holders and re-bound as
 * local delegates, so those reads stay live exactly as before.
 */
package com.spectrafilm.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.spectrafilm.engine.SpektraEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private const val OPEN_PLACEHOLDER_HOLD_MS = 600L

internal class EditorPhotoBrowse(
    val openPlaceholder: Bitmap?,
    val photoSwipe: PhotoSwipeNeighbours?,
    val folderStrip: FolderStripModel?,
)

@Composable
internal fun rememberEditorPhotoBrowse(
    ctx: Context,
    dispatchers: AppDispatchers,
    settings: AppSettings,
    profiles: List<String>,
    engine: SpektraEngine?,
    state: ParamsState,
    sourceUri: Uri?,
    sourceKind: SourceKind,
    sourceAuthorizationRequired: Boolean,
    rotationState: State<SourceRotation>,
    previewState: State<Bitmap?>,
    previewBusyState: State<Boolean>,
    interacting: State<Boolean>,
    previewTick: Int,
    incomingImageIntent: MutableState<Intent?>,
): EditorPhotoBrowse {
    val rotation by rotationState
    val preview by previewState
    val previewBusy by previewBusyState
    // #286: a display-only stand-in (the file's embedded thumbnail, else the provider's) shown
    // from the moment a source is picked or restored until the first engine render lands.
    // It never feeds the engine, an export or a cache. Keyed on the source identity only:
    // a later rotate does not reload it, because by then the render is on screen.
    var openPlaceholder by remember { mutableStateOf<Bitmap?>(null) }
    // The plain thumbnail the stand-in came from, kept so its film look can still be
    // rendered when the engine finishes loading after the thumbnail arrived (cold start).
    var placeholderSource by remember { mutableStateOf<Bitmap?>(null) }
    // The neighbour a swipe slid in, already in the film look: it stands in for the opened
    // photo until that photo's own graded thumbnail is ready, so the hand-off never steps
    // back to the plain thumbnail (film look -> plain -> film look).
    var swipeHandoff by remember { mutableStateOf<Pair<String, Bitmap>?>(null) }
    LaunchedEffect(sourceUri, sourceKind, sourceAuthorizationRequired) {
        val handoff = swipeHandoff?.takeIf { it.first == sourceUri?.toString() }?.second
        swipeHandoff = null
        openPlaceholder = handoff?.takeIf { !it.isRecycled && preview == null }
        val seeded = openPlaceholder != null
        placeholderSource = null
        val uri = sourceUri ?: return@LaunchedEffect
        if (sourceKind == SourceKind.DEMO || sourceAuthorizationRequired) return@LaunchedEffect
        OpenPlaceholder.load(
            ctx.applicationContext,
            uri,
            rotation,
            dispatchers.io,
            rawSource = sourceKind == SourceKind.RAW,
        ) { bmp ->
            // A render that already landed wins; a late thumbnail never covers it.
            if (preview == null) {
                // A graded swipe hand-off stays up; the look effect below replaces it.
                if (!seeded) openPlaceholder = bmp
                placeholderSource = bmp
            }
        }
    }
    // Give the stand-in the photo's film look (PlaceholderLook), so the real render changes
    // only detail when it lands. Re-runs for a better thumbnail, when the engine becomes
    // ready (a thumbnail that beat the engine still gets its look) and on every settings
    // change (a recipe restore that lands after the thumbnail re-grades it).
    LaunchedEffect(placeholderSource, engine, previewTick) {
        val src = placeholderSource ?: return@LaunchedEffect
        val e = engine ?: return@LaunchedEffect
        if (preview != null) return@LaunchedEffect
        val look = PlaceholderLook.render(
            ctx.applicationContext, e, src, state, dispatchers.default,
        ) ?: return@LaunchedEffect
        // Dropped if this effect was restarted meanwhile, or the real render won.
        val cancelled = !isActive
        val published = !cancelled && preview == null
        if (published) openPlaceholder = look.bitmap
        PlaceholderLook.report(look, published = published, cancelled = cancelled)
    }
    // Sideways swipe on the photo to the next / previous frame of the Library folder it was
    // opened from (PhotoBrowse). The neighbours' grid thumbnails ride in with the swipe;
    // opening one is the Library's own dispatch, so edits are kept exactly as they are when
    // stepping back to the grid and picking it.
    val currentUriString = sourceUri?.toString()
    val nextFrame = remember(currentUriString) { PhotoBrowse.neighbour(currentUriString, 1) }
    val previousFrame = remember(currentUriString) { PhotoBrowse.neighbour(currentUriString, -1) }
    // Each neighbour is graded with the settings it will open with (its own recipe, else the
    // defaults), read through the recipe queue so a just-flushed save of it is seen.
    val neighbourOpeningState: suspend (String) -> ParamsState = { uri ->
        val recipes = ctx.appContainer.recipes
        // peek, not readResult: a speculative read must not quarantine a corrupt recipe and
        // so hide its warning from the real restore when the photo is opened.
        val recipe = Recipes.keyFor(Uri.parse(uri))?.let { key ->
            RecipeWorkRuntime.submit { recipes.peek(key) }.await()
        }
        openingStateFor(recipe) { Recipes.resetToDefaults(it, settings, profiles) }
    }
    val nextThumb = rememberNeighbourBitmap(
        nextFrame, ctx.applicationContext, engine, neighbourOpeningState,
        busy = { previewBusy || interacting.value }, io = dispatchers.io, default = dispatchers.default,
    )
    val previousThumb = rememberNeighbourBitmap(
        previousFrame, ctx.applicationContext, engine, neighbourOpeningState,
        busy = { previewBusy || interacting.value }, io = dispatchers.io, default = dispatchers.default,
    )
    val photoSwipe = if (nextFrame == null && previousFrame == null) {
        null
    } else {
        PhotoSwipeNeighbours(
            sourceKey = currentUriString,
            next = nextThumb,
            previous = previousThumb,
            hasNext = nextFrame != null,
            hasPrevious = previousFrame != null,
            onCommit = { step ->
                val target = if (step > 0) nextFrame else previousFrame
                if (target != null) {
                    val carried = if (step > 0) nextThumb else previousThumb
                    swipeHandoff = carried?.let { target.uri to it }
                    incomingImageIntent.value = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
                }
            },
        )
    }
    // The folder strip (FolderStrip): the same Library folder, each frame drawn in this
    // photo's current look, opened from the frame counter on the canvas.
    val stripFrames = remember(currentUriString) { PhotoBrowse.frames }
    val stripIndex = remember(currentUriString, stripFrames) {
        stripFrames.indexOfFirst { it.uri == currentUriString }
    }
    val stripLooks = remember(engine) {
        engine?.let { FolderStripLooks(ctx.applicationContext, it, state, dispatchers.default) }
    }
    val folderStrip = if (stripIndex < 0 || stripFrames.size < 2) {
        null
    } else {
        FolderStripModel(
            frames = stripFrames,
            currentIndex = stripIndex,
            looks = stripLooks,
            lookKey = previewTick,
            paused = previewBusy || interacting.value,
            io = dispatchers.io,
            onOpen = { index ->
                stripFrames.getOrNull(index)?.let { target ->
                    incomingImageIntent.value = Intent(Intent.ACTION_VIEW, Uri.parse(target.uri))
                }
            },
        )
    }
    // Keep the stand-in under the fading-in render briefly, then drop it.
    LaunchedEffect(preview != null) {
        if (preview != null && openPlaceholder != null) {
            delay(OPEN_PLACEHOLDER_HOLD_MS)
            if (preview != null) {
                openPlaceholder = null
                placeholderSource = null
            }
        }
    }
    return EditorPhotoBrowse(openPlaceholder, photoSwipe, folderStrip)
}
