package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

private const val DUPLICATE_PAGE_ADVANCE_LIMIT = 3

@Immutable
data class CatalogRow(
    val addonId: String,
    val addonName: String,
    val addonBaseUrl: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val rawType: String = type.toApiString(),
    val items: List<MetaPreview>,
    val isLoading: Boolean = false,
    val hasMore: Boolean = true,
    val currentPage: Int = 0,
    val supportsSkip: Boolean = false,
    val skipStep: Int = 100,
    val nextSkip: Int = 0,
    val consecutiveDuplicatePages: Int = 0,
    val extraArgs: Map<String, String> = emptyMap(),
    /**
     * Shifts the numbers item keys are built from, so a card keeps the same key for as long as
     * it stays in the row. Items prepended by a refresh take the numbers below it rather than
     * pushing everyone up, which would hand an existing key to a different item. Decremented by
     * the number of prepended items, reset whenever the row is rebuilt from scratch.
     */
    val keyOffset: Int = 0
) {
    val apiType: String
        get() = type.toApiString(rawType)
}

fun CatalogRow.stableKey(): String {
    return catalogRowStableKey(addonId, addonBaseUrl, apiType, catalogId)
}

fun CatalogRow.legacyKey(): String {
    return catalogRowLegacyKey(addonId, apiType, catalogId)
}

/**
 * Identity-based item key, so a key follows its item when the list shifts instead of staying
 * pinned to a slot. [occurrence] disambiguates an id appearing twice in the same row.
 */
/**
 * Key for the item at [index], stable for the life of that item in the row: it does not change
 * when a refresh prepends items, and it does not change when a placeholder becomes real, which
 * is what keeps the focused node alive through both.
 */
fun CatalogRow.stableItemKeyAt(index: Int): String {
    return "${stableKey()}_${keyOffset + index}"
}

fun CatalogRow.stableItemKey(item: MetaPreview, occurrence: Int = 0): String {
    val identity = "${stableKey()}_${item.apiType}:${item.id}"
    return if (occurrence == 0) identity else "$identity#$occurrence"
}

/** Item keys for the whole row, aligned with [items], for callers that only have an index. */
fun CatalogRow.stableItemKeys(): List<String> {
    val seen = HashMap<String, Int>()
    return items.map { item ->
        val identity = "${item.apiType}:${item.id}"
        val occurrence = seen.getOrDefault(identity, 0)
        seen[identity] = occurrence + 1
        stableItemKey(item, occurrence)
    }
}

fun catalogRowStableKey(
    addonId: String,
    addonBaseUrl: String,
    type: String,
    catalogId: String
): String {
    val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
    val baseUrlKey = "${normalizedBaseUrl.hashCode()}_${normalizedBaseUrl.length}"
    return "${addonId}_${baseUrlKey}_${type}_${catalogId}"
}

fun catalogRowLegacyKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

fun CatalogRow.nextCatalogSkip(): Int {
    val fallback = (currentPage + 1) * skipStep
    return if (nextSkip > 0) nextSkip else fallback
}

fun CatalogRow.mergeCatalogPage(
    page: CatalogRow,
    incomingItems: List<MetaPreview> = page.items
): CatalogRow {
    val existingIds = items.asSequence()
        .map { "${it.apiType}:${it.id}" }
        .toHashSet()
    val newItems = incomingItems.filter { item ->
        "${item.apiType}:${item.id}" !in existingIds
    }
    val mergedItems = items + newItems
    val requestedSkip = nextCatalogSkip()
    val duplicatePageCount = if (newItems.isEmpty() && page.items.isNotEmpty()) {
        consecutiveDuplicatePages + 1
    } else {
        0
    }
    val advancedSkip = if (newItems.isEmpty()) {
        if (page.nextSkip > requestedSkip) page.nextSkip else requestedSkip + page.items.size.coerceAtLeast(1)
    } else {
        page.nextSkip
    }
    val hasMore = when {
        page.items.isEmpty() -> false
        newItems.isNotEmpty() -> page.hasMore
        duplicatePageCount < DUPLICATE_PAGE_ADVANCE_LIMIT -> page.hasMore && advancedSkip > requestedSkip
        else -> false
    }

    return page.copy(
        items = mergedItems,
        hasMore = hasMore,
        currentPage = currentPage + 1,
        nextSkip = advancedSkip,
        consecutiveDuplicatePages = duplicatePageCount,
        // Carried over from the row being paginated, not taken from the incoming page: the
        // existing items keep their place at the head, so they have to keep their key numbers.
        keyOffset = keyOffset
    )
}
