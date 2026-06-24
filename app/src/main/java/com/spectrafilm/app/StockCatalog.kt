/*
 * Spektrafilm for Android — film/print stock catalog. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Loads assets/spektra/catalog.json (via the AssetManager) and turns the raw engine
 * profile ids (e.g. "kodak_portra_400") into friendly, grouped picker entries
 * ("Kodak Portra 400" under "Color Negative (Still)"). Stocks carry a `kind`
 * (film / print_film / print_paper) so the Film profile dropdown can show capture
 * stocks while the Print dropdown shows print papers. Anything the engine reports but
 * the catalog doesn't describe still appears, falling back to its raw id.
 */
package com.spectrafilm.app

import android.content.Context
import org.json.JSONObject

/** One catalog entry, keyed by its engine profile id. */
data class StockEntry(
    val id: String,
    val displayName: String,
    val manufacturer: String,
    val groupId: String,
    val kind: String,         // "film" | "print_film" | "print_paper"
    val iso: Int?,
    val balance: String?,
    val summary: String,
    val order: Int,
)

/** A catalog group (category) with a human title and sort order. */
data class StockGroup(val id: String, val title: String, val order: Int)

/** True when this entry is a colour-reversal (slide) film — the "view as positive" stocks. */
fun StockEntry.isReversal(): Boolean = groupId == StockCatalog.GROUP_COLOR_REVERSAL

/** An option shown in a profile dropdown: an id plus its display label and group. */
data class ProfileOption(val id: String, val label: String, val groupTitle: String)

object StockCatalog {

    private const val ASSET_PATH = "spektra/catalog.json"

    /** Catalog group id for colour-reversal (slide) films — the natural "view as positive" stocks. */
    const val GROUP_COLOR_REVERSAL = "color_reversal"

    @Volatile private var groupsCache: List<StockGroup>? = null
    @Volatile private var stocksCache: Map<String, StockEntry>? = null

    private fun ensureLoaded(ctx: Context) {
        if (stocksCache != null && groupsCache != null) return
        val (groups, stocks) = runCatching {
            val text = ctx.assets.open(ASSET_PATH).use { it.readBytes().decodeToString() }
            val root = JSONObject(text)

            val groupsArr = root.optJSONArray("groups")
            val groups = buildList {
                if (groupsArr != null) for (i in 0 until groupsArr.length()) {
                    val g = groupsArr.optJSONObject(i) ?: continue
                    add(StockGroup(g.optString("id"), g.optString("title", g.optString("id")), g.optInt("order", i)))
                }
            }

            val stocksObj = root.optJSONObject("stocks") ?: JSONObject()
            val stocks = LinkedHashMap<String, StockEntry>()
            for (id in stocksObj.keys()) {
                val o = stocksObj.optJSONObject(id) ?: continue
                stocks[id] = StockEntry(
                    id = id,
                    displayName = o.optString("name", id),
                    manufacturer = o.optString("manufacturer", ""),
                    groupId = o.optString("group", ""),
                    kind = o.optString("kind", "film"),
                    iso = if (o.has("iso")) o.optInt("iso") else null,
                    balance = if (o.has("balance")) o.optString("balance") else null,
                    summary = o.optString("summary", ""),
                    order = o.optInt("order", 0),
                )
            }
            groups to (stocks as Map<String, StockEntry>)
        }.getOrDefault(emptyList<StockGroup>() to emptyMap())
        groupsCache = groups
        stocksCache = stocks
    }

    fun stocks(ctx: Context): Map<String, StockEntry> {
        ensureLoaded(ctx); return stocksCache ?: emptyMap()
    }

    fun groups(ctx: Context): List<StockGroup> {
        ensureLoaded(ctx); return groupsCache ?: emptyList()
    }

    fun entry(ctx: Context, id: String): StockEntry? = stocks(ctx)[id]

    /** Friendly display name for a profile id, or the raw id if not catalogued. */
    fun displayName(ctx: Context, id: String): String = stocks(ctx)[id]?.displayName ?: id

    private fun groupTitle(ctx: Context, groupId: String): String =
        groups(ctx).firstOrNull { it.id == groupId }?.title ?: groupId

    /** Group sort order, falling back to a large value so unknown groups sort last. */
    private fun groupOrder(ctx: Context, groupId: String): Int =
        groups(ctx).firstOrNull { it.id == groupId }?.order ?: Int.MAX_VALUE

    /** True if [id] denotes a print medium (print film or print paper). */
    fun isPrintKind(ctx: Context, id: String): Boolean =
        stocks(ctx)[id]?.kind?.let { it == "print_paper" || it == "print_film" } ?: false

    /** True if [id] is a colour-reversal (slide) film — best viewed as a positive (Slide mode). */
    fun isReversalFilm(ctx: Context, id: String): Boolean = entry(ctx, id)?.isReversal() ?: false

    /**
     * Build dropdown options for [available] engine profile ids, filtered to a side:
     * "film" keeps capture stocks (kind == "film"), "print" keeps print media
     * (print_paper / print_film). Catalogued options are sorted by group order then
     * stock order; ids not in the catalog are appended (their group title is the raw id).
     *
     * Profiles the catalog doesn't classify are included on the [forFilm] = true side so
     * they remain selectable rather than vanishing.
     */
    fun optionsFor(ctx: Context, available: List<String>, forFilm: Boolean): List<ProfileOption> {
        val stocks = stocks(ctx)
        val matched = available.filter { id ->
            val e = stocks[id]
            if (e == null) forFilm // uncatalogued profiles fall to the Film side
            else if (forFilm) e.kind == "film" else (e.kind == "print_paper" || e.kind == "print_film")
        }
        return matched.sortedWith(
            compareBy({ groupOrder(ctx, stocks[it]?.groupId ?: "") }, { stocks[it]?.order ?: 0 }, { it }),
        ).map { id ->
            val e = stocks[id]
            ProfileOption(
                id = id,
                label = e?.displayName ?: id,
                groupTitle = e?.let { groupTitle(ctx, it.groupId) } ?: "Other",
            )
        }
    }
}
