/*
 * Spektrafilm for Android — the app's icon namespace. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Every icon is the Material Symbols (Outlined) glyph the Pen board uses ("Whole app
 * proposed"); see MaterialSymbols.kt. Call sites keep using SpectraIcons.X, so the mapping
 * from a role in the app to a glyph lives in this one place. Colour is left to the caller
 * through the Icon tint.
 */
package com.spectrafilm.app

import androidx.compose.ui.graphics.vector.ImageVector

object SpectraIcons {

    /** Near-black editor canvas colour (photo-only mode and the export mask scrim). */
    val nearBlackCanvas = SpektraBrand.canvasNearBlack

    // Editor groups (Pen: Film and look panel, tool buttons)
    val Input: ImageVector get() = MaterialSymbols.Exposure
    val ImportRaw: ImageVector get() = MaterialSymbols.RawOn
    val Simulation: ImageVector get() = MaterialSymbols.CameraRoll
    val Grain: ImageVector get() = MaterialSymbols.Grain
    val Preflash: ImageVector get() = MaterialSymbols.FlashOn
    val ContrastMask: ImageVector get() = MaterialSymbols.Contrast
    val Halation: ImageVector get() = MaterialSymbols.Flare
    val Couplers: ImageVector get() = MaterialSymbols.BlurOn
    val Glare: ImageVector get() = MaterialSymbols.LightMode
    val Masks: ImageVector get() = MaterialSymbols.Gradient
    val Experimental: ImageVector get() = MaterialSymbols.Science
    val Display: ImageVector get() = MaterialSymbols.Monitor
    val Output: ImageVector get() = MaterialSymbols.Output
    val ToneCurve: ImageVector get() = MaterialSymbols.ShowChart
    val Presets: ImageVector get() = MaterialSymbols.Style
    val Source: ImageVector get() = MaterialSymbols.Photo
    val WhiteBalance: ImageVector get() = MaterialSymbols.WbSunny
    val Paper: ImageVector get() = MaterialSymbols.Print

    // Stages (Pen: stage row)
    val StageLook: ImageVector get() = MaterialSymbols.AutoAwesome
    val StageFilm: ImageVector get() = MaterialSymbols.CameraRoll
    val StagePrint: ImageVector get() = MaterialSymbols.Print
    val StageScan: ImageVector get() = MaterialSymbols.DocumentScanner
    val StageFinish: ImageVector get() = MaterialSymbols.Tune

    // App bar and actions
    val Export: ImageVector get() = MaterialSymbols.Download
    val Share: ImageVector get() = MaterialSymbols.Share
    val SourceImage: ImageVector get() = MaterialSymbols.PhotoLibrary
    val Settings: ImageVector get() = MaterialSymbols.Settings
    val Help: ImageVector get() = MaterialSymbols.Help
    val Info: ImageVector get() = MaterialSymbols.Info
    val More: ImageVector get() = MaterialSymbols.MoreVert
    val MoreVert: ImageVector get() = MaterialSymbols.MoreVert
    val OpenPhoto: ImageVector get() = MaterialSymbols.AddPhotoAlternate
    val Crop: ImageVector get() = MaterialSymbols.Crop
    val Check: ImageVector get() = MaterialSymbols.Check
    val Histogram: ImageVector get() = MaterialSymbols.BarChart
    val Clipping: ImageVector get() = MaterialSymbols.Warning
    val Compare: ImageVector get() = MaterialSymbols.Compare
    val ChevronDown: ImageVector get() = MaterialSymbols.ExpandMore
    val AspectRatio: ImageVector get() = MaterialSymbols.AspectRatio
    val Rotate: ImageVector get() = MaterialSymbols.RotateRight
    val Reset: ImageVector get() = MaterialSymbols.RestartAlt
    val Cancel: ImageVector get() = MaterialSymbols.Close
    val Search: ImageVector get() = MaterialSymbols.Search
    val Add: ImageVector get() = MaterialSymbols.Add
    val Album: ImageVector get() = MaterialSymbols.PhotoAlbum
    val AddToAlbum: ImageVector get() = MaterialSymbols.LibraryAdd
    val RemoveFromAlbum: ImageVector get() = MaterialSymbols.DoNotDisturbOn
    val SetCover: ImageVector get() = MaterialSymbols.Image
    val ImportFiles: ImageVector get() = MaterialSymbols.FileOpen
    val Folder: ImageVector get() = MaterialSymbols.FolderOpen
    val Play: ImageVector get() = MaterialSymbols.PlayArrow
    val Pause: ImageVector get() = MaterialSymbols.Pause
    val Slideshow: ImageVector get() = MaterialSymbols.Slideshow
    val Sort: ImageVector get() = MaterialSymbols.Sort
    val GridView: ImageVector get() = MaterialSymbols.GridView
    val JustifiedView: ImageVector get() = MaterialSymbols.ViewQuilt
    val Confirm: ImageVector get() = MaterialSymbols.Check
    val Back: ImageVector get() = MaterialSymbols.ArrowBack
    val Eyedropper: ImageVector get() = MaterialSymbols.Colorize
    val Loupe: ImageVector get() = MaterialSymbols.ZoomIn
    val Undo: ImageVector get() = MaterialSymbols.Undo
    val Redo: ImageVector get() = MaterialSymbols.Redo
}
