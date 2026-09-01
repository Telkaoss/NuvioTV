package com.nuvio.tv.ui.screens.home

/** How a row's focused index should follow its content across a refresh. */
internal sealed interface RowFocusRelocation {
    object Unchanged : RowFocusRelocation

    /** The user moved the ring. Remember what they are pointing at now. */
    data class Noted(val index: Int, val identity: String?) : RowFocusRelocation

    /** The content moved under a ring that stayed put. Follow the remembered item. */
    data class Relocated(val index: Int, val from: Int) : RowFocusRelocation

    /**
     * The remembered item is gone and the ring has not moved, so the user did not do this.
     * Typically a row composed while it still held placeholders. Adopt what holds that place
     * now, or the row keeps a dead reference and stops following its content.
     */
    data class Readopted(val identity: String) : RowFocusRelocation
}

/**
 * @param storedIndex the index the focus ring is on, or null when the row has none.
 * @param recordedIndex the index this mechanism last wrote itself.
 * @param rememberedIdentity the identity of the item the ring was on.
 * @param currentIdentities identities of the row as it is now.
 */
internal fun resolveRowFocusRelocation(
    storedIndex: Int?,
    recordedIndex: Int?,
    rememberedIdentity: String?,
    currentIdentities: List<String>
): RowFocusRelocation {
    if (storedIndex == null) return RowFocusRelocation.Unchanged

    if (storedIndex != recordedIndex) {
        return RowFocusRelocation.Noted(storedIndex, currentIdentities.getOrNull(storedIndex))
    }

    val relocated = rememberedIdentity
        ?.let { currentIdentities.indexOf(it) }
        ?.takeIf { it >= 0 }
    if (relocated != null) {
        return if (relocated == storedIndex) RowFocusRelocation.Unchanged
        else RowFocusRelocation.Relocated(relocated, storedIndex)
    }

    val identityHere = currentIdentities.getOrNull(storedIndex)
    return if (identityHere != null && identityHere != rememberedIdentity) {
        RowFocusRelocation.Readopted(identityHere)
    } else {
        RowFocusRelocation.Unchanged
    }
}

/**
 * What identifies an item across a refresh, independent of where it sits. Continue Watching
 * reuses the key its cards are already composed with: `hashCode()` folds in the playback
 * position, so an item stopped being itself as soon as it was watched.
 */
internal fun modernPayloadIdentity(payload: ModernPayload): String {
    return when (payload) {
        is ModernPayload.Catalog -> "${payload.itemType}:${payload.itemId}"
        is ModernPayload.CollectionFolder -> "folder:${payload.folderId}"
        is ModernPayload.ContinueWatching -> continueWatchingItemKey(payload.item)
    }
}
