package com.nuvio.tv.ui.screens.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.SearchHistoryDataStore
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.DiscoverLocation
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.core.util.isUnreleased
import com.nuvio.tv.domain.repository.AddonRepository
import java.time.LocalDate
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val searchHistoryDataStore: SearchHistoryDataStore,
    private val watchProgressRepository: com.nuvio.tv.domain.repository.WatchProgressRepository,
    private val watchedSeriesStateHolder: com.nuvio.tv.data.local.WatchedSeriesStateHolder,
    val posterOptions: com.nuvio.tv.ui.components.posteroptions.PosterOptionsController,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    /** Saved focus state for restoring scroll/focus position after returning from details. */
    var savedFocusRowKey: String? = null
    var savedFocusItemIndex: Int = -1
    var savedRowScrollPositions: Map<String, Pair<Int, Int>> = emptyMap()
    var hasSavedSearchFocus: Boolean = false

    private val _watchedMovieIds = MutableStateFlow<Set<String>>(emptySet())
    val watchedMovieIds: StateFlow<Set<String>> = _watchedMovieIds.asStateFlow()
    val watchedSeriesIds: StateFlow<Set<String>> = watchedSeriesStateHolder.fullyWatchedSeriesIds

    private val catalogsMap = linkedMapOf<String, CatalogRow>()
    private val catalogOrder = mutableListOf<String>()

    private var activeSearchJobs: List<Job> = emptyList()
    private var discoverJob: Job? = null
    private var catalogRowsUpdateJob: Job? = null
    private var suggestionJob: Job? = null
    private var hasRenderedFirstCatalog = false
    private var pendingCatalogResponses = 0
    private var revealBatchAfterNextDiscoverFetch = false
    private var hideUnreleasedContent = false

    private companion object {
        const val DISCOVER_INITIAL_LIMIT = 100
        const val DISCOVER_SHOW_MORE_BATCH = 50
        const val SUGGESTION_DEBOUNCE_MS = 150L
        const val MAX_SUGGESTIONS = 12
        const val MAX_RECENT_SEARCHES = 8
    }

    init {
        posterOptions.bind(viewModelScope)
        viewModelScope.launch {
            watchProgressRepository.observeWatchedMovieIds()
                .collect { ids -> _watchedMovieIds.value = ids }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.discoverLocation.distinctUntilChanged().collectLatest { location ->
                _uiState.update { it.copy(discoverLocation = location) }
                if (location == DiscoverLocation.OFF) {
                    discoverJob?.cancel()
                    discoverJob = null
                    revealBatchAfterNextDiscoverFetch = false
                    _uiState.update {
                        it.copy(
                            discoverInitialized = false,
                            discoverLoading = false,
                            discoverLoadingMore = false,
                            discoverCatalogs = emptyList(),
                            selectedDiscoverType = "movie",
                            selectedDiscoverCatalogKey = null,
                            selectedDiscoverGenre = null,
                            discoverResults = emptyList(),
                            pendingDiscoverResults = emptyList(),
                            discoverHasMore = true,
                            discoverPage = 1
                        )
                    }
                }
            }
        }
        // Combine all layout preference flows into a single collector to reduce coroutine overhead
        viewModelScope.launch {
            combine(
                layoutPreferenceDataStore.posterCardWidthDp,
                layoutPreferenceDataStore.posterLabelsEnabled,
                layoutPreferenceDataStore.catalogAddonNameEnabled,
                layoutPreferenceDataStore.posterCardHeightDp,
                layoutPreferenceDataStore.posterCardCornerRadiusDp
            ) { widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp ->
                LayoutPrefs(widthDp, labelsEnabled, addonNameEnabled, heightDp, cornerRadiusDp)
            }.collectLatest { prefs ->
                _uiState.update {
                    it.copy(
                        posterCardWidthDp = prefs.widthDp,
                        posterLabelsEnabled = prefs.labelsEnabled,
                        catalogAddonNameEnabled = prefs.addonNameEnabled,
                        posterCardHeightDp = prefs.heightDp,
                        posterCardCornerRadiusDp = prefs.cornerRadiusDp
                    )
                }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.catalogTypeSuffixEnabled.collectLatest { enabled ->
                _uiState.update { it.copy(catalogTypeSuffixEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            layoutPreferenceDataStore.hideUnreleasedContent.collectLatest { enabled ->
                hideUnreleasedContent = enabled
                scheduleCatalogRowsUpdate()
            }
        }
        viewModelScope.launch {
            searchHistoryDataStore.recentSearches.collectLatest { recent ->
                _uiState.update { it.copy(recentSearches = recent.take(MAX_RECENT_SEARCHES)) }
            }
        }
    }

    private data class LayoutPrefs(
        val widthDp: Int,
        val labelsEnabled: Boolean,
        val addonNameEnabled: Boolean,
        val heightDp: Int,
        val cornerRadiusDp: Int
    )

    fun ensureDiscoverLoaded() {
        val state = _uiState.value
        if (state.discoverLocation == DiscoverLocation.OFF) return
        if (state.discoverInitialized || state.discoverLoading) return
        viewModelScope.launch { loadDiscoverCatalogs() }
    }

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> onQueryChanged(event.query)
            SearchEvent.SubmitSearch -> submitSearch()
            SearchEvent.ClearRecentSearches -> clearRecentSearches()
            is SearchEvent.LoadMoreCatalog -> loadMoreCatalogItems(
                catalogId = event.catalogId,
                addonId = event.addonId,
                type = event.type
            )
            is SearchEvent.SelectDiscoverType -> selectDiscoverType(event.type)
            is SearchEvent.SelectDiscoverCatalog -> selectDiscoverCatalog(event.catalogKey)
            is SearchEvent.SelectDiscoverGenre -> selectDiscoverGenre(event.genre)
            SearchEvent.LoadNextDiscoverResults -> loadNextDiscoverResults()
            SearchEvent.Retry -> performSearch(uiState.value.submittedQuery.ifBlank { uiState.value.query })
        }
    }

    private fun onQueryChanged(query: String) {
        _uiState.update {
            val trimmedInput = query.trim()
            val submitted = it.submittedQuery.trim()
            it.copy(
                query = query,
                error = null,
                isSearching = false,
                catalogRows = if (trimmedInput == submitted) it.catalogRows else emptyList()
            )
        }

        // Search is explicit on submit only; stop any in-flight requests while editing.
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()

        fetchSuggestions(query.trim())
    }

    private fun fetchSuggestions(query: String) {
        suggestionJob?.cancel()

        if (query.length < 2) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        // Don't show suggestions if the query already matches the submitted search
        if (query == _uiState.value.submittedQuery.trim() && _uiState.value.catalogRows.isNotEmpty()) {
            _uiState.update { it.copy(suggestions = emptyList()) }
            return
        }

        suggestionJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGESTION_DEBOUNCE_MS)

            val addons = try {
                addonRepository.getInstalledAddons().first().enabledAddons()
            } catch (_: Exception) {
                return@launch
            }

            val allTargets = buildSearchTargets(addons)
            val firstAddonId = allTargets.firstOrNull()?.first?.id
            val searchTargets = if (firstAddonId != null) allTargets.filter { it.first.id == firstAddonId } else emptyList()
            if (searchTargets.isEmpty()) {
                _uiState.update { it.copy(suggestions = emptyList()) }
                return@launch
            }

            // Int = arrival index, used as the final tie-breaker (addons return
            // results already ranked by popularity).
            val collected = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, SearchSuggestion>>()
            val arrivalOrder = java.util.concurrent.atomic.AtomicInteger(0)
            val normQuery = normalizeForSearch(query)
            val suggestionJobs = searchTargets.map { (addon, catalog) ->
                launch {
                    try {
                        catalogRepository.getCatalog(
                            addonBaseUrl = addon.baseUrl,
                            addonId = addon.id,
                            addonName = addon.displayName,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            type = catalog.apiType,
                            skip = 0,
                            skipStep = 100,
                            extraArgs = mapOf("search" to query),
                            supportsSkip = false
                        ).collect { result ->
                            if (result is NetworkResult.Success && _uiState.value.query.trim() == query) {
                                var added = false
                                result.data.items.forEach { item ->
                                    val suggestion = item.toSearchSuggestion(addon.baseUrl)
                                    // Dedup by title+year: merges the same show coming from two
                                    // catalogs while keeping remakes apart (One Piece 1999 vs 2023).
                                    val key = "${normalizeForSearch(suggestion.name)}|${suggestion.year ?: item.id}"
                                    val entry = arrivalOrder.getAndIncrement() to suggestion
                                    val kept = collected.merge(key, entry) { existing, incoming ->
                                        val ep = existing.second.popularity ?: Double.NEGATIVE_INFINITY
                                        val ip = incoming.second.popularity ?: Double.NEGATIVE_INFINITY
                                        if (ip > ep) incoming else existing  // keep the more popular
                                    }
                                    if (kept === entry) added = true
                                }
                                // Push updated suggestions immediately as each addon responds.
                                // Rank by relevance tier first (exact > word-prefix > mid-word
                                // substring), then by popularity within a tier.
                                if (added) {
                                    val sorted = collected.values
                                        // Keep title matches, plus popular alternate-title hits
                                        // (e.g. "Money Heist" for "casa"); drop scoreless noise.
                                        .filter {
                                            normalizeForSearch(it.second.name).contains(normQuery) ||
                                                it.second.popularity != null
                                        }
                                        // Relevance tier, then popularity, then rating; nulls-last so
                                        // addons without scores keep their own order.
                                        .sortedWith(
                                            compareBy<Pair<Int, SearchSuggestion>> { relevanceTier(it.second.name, normQuery) }
                                                .thenByDescending { it.second.popularity ?: Double.NEGATIVE_INFINITY }
                                                .thenByDescending { it.second.rating ?: Float.NEGATIVE_INFINITY }
                                                .thenBy { it.first }
                                        )
                                        .map { it.second }
                                        .take(MAX_SUGGESTIONS)
                                    _uiState.update { it.copy(suggestions = sorted) }
                                }
                            }
                        }
                    } catch (_: Exception) {
                        // Ignore per-catalog errors for suggestions
                    }
                }
            }

            suggestionJobs.joinAll()
        }
    }

    private fun submitSearch() {
        performSearch(_uiState.value.query)
    }

    private fun clearRecentSearches() {
        viewModelScope.launch {
            searchHistoryDataStore.clearRecentSearches()
        }
    }

    /** Records history when a dropdown suggestion is picked (it opens detail
     *  directly, bypassing [performSearch]). */
    fun recordRecentSearch(query: String) {
        val q = query.trim()
        if (q.length >= 2) {
            viewModelScope.launch {
                searchHistoryDataStore.saveRecentSearch(q, MAX_RECENT_SEARCHES)
            }
        }
    }

    private fun performSearch(rawQuery: String) {
        val query = rawQuery.trim()
        suggestionJob?.cancel()
        _uiState.update {
            it.copy(
                submittedQuery = query,
                query = rawQuery,
                suggestions = emptyList()
            )
        }

        if (query.length >= 2) {
            viewModelScope.launch {
                searchHistoryDataStore.saveRecentSearch(query, MAX_RECENT_SEARCHES)
            }
        }

        // Cancel any in-flight work from the previous query.
        activeSearchJobs.forEach { it.cancel() }
        activeSearchJobs = emptyList()
        catalogRowsUpdateJob?.cancel()

        catalogsMap.clear()
        catalogOrder.clear()
        hasRenderedFirstCatalog = false
        pendingCatalogResponses = 0

        if (query.length < 2) {
            _uiState.update {
                it.copy(
                    isSearching = false,
                    error = null,
                    catalogRows = emptyList()
                )
            }
            ensureDiscoverLoaded()
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = true, error = null, catalogRows = emptyList()) }

            val addons = try {
                addonRepository.getInstalledAddons().first().enabledAddons()
            } catch (e: Exception) {
                _uiState.update { it.copy(isSearching = false, error = e.message ?: context.getString(com.nuvio.tv.R.string.search_error_load_addons_failed)) }
                return@launch
            }

            _uiState.update { it.copy(installedAddons = addons) }

            val searchTargets = buildSearchTargets(addons)

            if (searchTargets.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        error = context.getString(R.string.search_error_no_catalogs),
                        catalogRows = emptyList()
                    )
                }
                return@launch
            }

            // Preserve addon manifest order.
            searchTargets.forEach { (addon, catalog) ->
                val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                if (key !in catalogOrder) {
                    catalogOrder.add(key)
                }
            }

            // Emit placeholder rows with shimmer items so the UI shows
            // skeleton rows immediately instead of a spinner.
            val placeholderRows = searchTargets.map { (addon, catalog) ->
                val key = catalogKey(addonId = addon.id, type = catalog.apiType, catalogId = catalog.id)
                val fakeItems = (0 until 8).map { i ->
                    MetaPreview(
                        id = "__placeholder_${key}_$i",
                        type = ContentType.fromString(catalog.apiType),
                        rawType = catalog.apiType,
                        name = " ",
                        poster = "placeholder://empty",
                        posterShape = PosterShape.POSTER,
                        background = null,
                        logo = null,
                        description = null,
                        releaseInfo = " ",
                        imdbRating = null,
                        genres = emptyList()
                    )
                }
                CatalogRow(
                    addonId = addon.id,
                    addonName = addon.displayName,
                    addonBaseUrl = addon.baseUrl,
                    catalogId = catalog.id,
                    catalogName = catalog.name,
                    type = ContentType.fromString(catalog.apiType),
                    rawType = catalog.apiType,
                    items = fakeItems,
                    isLoading = true,
                    hasMore = false,
                    currentPage = 0,
                    supportsSkip = false,
                    skipStep = 0,
                    extraArgs = emptyMap()
                )
            }
            _uiState.update { it.copy(catalogRows = placeholderRows) }

            val jobs = searchTargets.map { (addon, catalog) ->
                viewModelScope.launch {
                    loadCatalog(addon, catalog, query)
                }
            }
            pendingCatalogResponses = jobs.size
            activeSearchJobs = jobs

            // Wait for all jobs to complete so we can stop showing the global loading state.
            viewModelScope.launch {
                try {
                    jobs.joinAll()
                } catch (_: Exception) {
                    // Cancellations are expected when query changes.
                } finally {
                    if (uiState.value.submittedQuery.trim() == query) {
                        _uiState.update { it.copy(isSearching = false) }
                    }
                }
            }
        }
    }

    private suspend fun loadCatalog(addon: Addon, catalog: CatalogDescriptor, query: String) {
        val supportsSkip = catalog.supportsExtra("skip")
        val skipStep = catalog.skipStep()
        catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = skipStep,
            extraArgs = mapOf("search" to query),
            supportsSkip = supportsSkip
        ).collect { result ->
            when (result) {
                is NetworkResult.Success -> {
                    if (uiState.value.submittedQuery.trim() != query) return@collect
                    val key = catalogKey(
                        addonId = addon.id,
                        type = catalog.apiType,
                        catalogId = catalog.id
                    )
                    catalogsMap[key] = result.data
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    scheduleCatalogRowsUpdate()
                }
                is NetworkResult.Error -> {
                    if (uiState.value.submittedQuery.trim() != query) return@collect
                    pendingCatalogResponses = (pendingCatalogResponses - 1).coerceAtLeast(0)
                    // Ignore per-catalog errors unless we have nothing to show.
                    if (catalogsMap.isEmpty()) {
                        _uiState.update { it.copy(error = result.message ?: context.getString(com.nuvio.tv.R.string.search_error_failed)) }
                    }
                    scheduleCatalogRowsUpdate()
                }
                NetworkResult.Loading -> {
                    // No-op; screen shows global loading when empty.
                }
            }
        }
    }

    private fun loadMoreCatalogItems(catalogId: String, addonId: String, type: String) {
        val key = catalogKey(addonId = addonId, type = type, catalogId = catalogId)
        val currentRow = catalogsMap[key] ?: return

        if (currentRow.isLoading || !currentRow.hasMore) return

        catalogsMap[key] = currentRow.copy(isLoading = true)
        scheduleCatalogRowsUpdate()

        val query = uiState.value.query.trim()
        if (query.isBlank()) return

        viewModelScope.launch {
            val addon = uiState.value.installedAddons.find { it.id == addonId } ?: run {
                catalogsMap[key] = currentRow.copy(isLoading = false)
                scheduleCatalogRowsUpdate()
                return@launch
            }

            val nextSkip = (currentRow.currentPage + 1) * currentRow.skipStep
            catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = currentRow.catalogName,
                type = currentRow.apiType,
                skip = nextSkip,
                skipStep = currentRow.skipStep,
                extraArgs = mapOf("search" to query),
                supportsSkip = currentRow.supportsSkip
            ).collect { result ->
                when (result) {
                    is NetworkResult.Success -> {
                        val existingIds = currentRow.items.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toHashSet()
                        val newUniqueItems = result.data.items.filter { item ->
                            "${item.apiType}:${item.id}" !in existingIds
                        }
                        val mergedItems = currentRow.items + newUniqueItems
                        val hasMore = if (newUniqueItems.isEmpty()) false else result.data.hasMore
                        catalogsMap[key] = result.data.copy(items = mergedItems, hasMore = hasMore)
                        scheduleCatalogRowsUpdate()
                    }
                    is NetworkResult.Error -> {
                        catalogsMap[key] = currentRow.copy(isLoading = false)
                        scheduleCatalogRowsUpdate()
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun scheduleCatalogRowsUpdate() {
        catalogRowsUpdateJob?.cancel()
        catalogRowsUpdateJob = viewModelScope.launch {
            if (!hasRenderedFirstCatalog && catalogsMap.isNotEmpty()) {
                hasRenderedFirstCatalog = true
                updateCatalogRowsNow()
                return@launch
            }
            val debounceMs = when {
                pendingCatalogResponses > 5 -> 220L
                pendingCatalogResponses > 0 -> 140L
                else -> 90L
            }
            kotlinx.coroutines.delay(debounceMs)
            updateCatalogRowsNow()
        }
    }

    private fun updateCatalogRowsNow() {
        _uiState.update { state ->
            val orderedRows = catalogOrder.map { key ->
                catalogsMap[key]
                    ?: state.catalogRows.find {
                        catalogKey(addonId = it.addonId, type = it.rawType, catalogId = it.catalogId) == key
                    }
            }.filterNotNull().filter { row ->
                // Keep placeholder rows (shimmer) and rows with real items.
                // Drop rows that came back empty from the API.
                val isPlaceholder = row.isLoading &&
                    row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true
                isPlaceholder || row.items.isNotEmpty()
            }
            val filteredRows = if (hideUnreleasedContent) {
                val today = LocalDate.now()
                orderedRows.map { row ->
                    if (row.isLoading && row.items.firstOrNull()?.id?.startsWith("__placeholder_") == true) {
                        row
                    } else {
                        row.filterReleasedItems(today)
                    }
                }
            } else {
                orderedRows
            }
            state.copy(
                catalogRows = filteredRows
            )
        }
    }

    private suspend fun loadDiscoverCatalogs() {
        if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return
        _uiState.update { it.copy(discoverLoading = true) }
        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (_: Exception) {
            _uiState.update { it.copy(discoverInitialized = true, discoverLoading = false) }
            return
        }

        val discoverCatalogs = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    !(catalog.supportsExtra("search") &&
                        catalog.extra.any { it.name.equals("search", ignoreCase = true) && it.isRequired })
                }
                .map { catalog ->
                    val genres = catalog.extra
                        .firstOrNull { it.name.equals("genre", ignoreCase = true) }
                        ?.options
                        .orEmpty()
                    DiscoverCatalog(
                        key = "${addon.id}_${catalog.apiType}_${catalog.id}",
                        addonId = addon.id,
                        addonName = addon.displayName,
                        addonBaseUrl = addon.baseUrl,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        genres = genres,
                        supportsSkip = catalog.supportsExtra("skip"),
                        skipStep = catalog.skipStep()
                    )
                }
        }

        val availableTypes = discoverCatalogs.map { it.type }.distinct()
        val currentType = _uiState.value.selectedDiscoverType
        val selectedType = if (currentType in availableTypes) currentType else availableTypes.firstOrNull() ?: "movie"
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = discoverCatalogs,
            selectedType = selectedType,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null

        _uiState.update {
            it.copy(
                installedAddons = addons,
                discoverCatalogs = discoverCatalogs,
                selectedDiscoverType = selectedType,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverInitialized = true,
                discoverLoading = false,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverHasMore = true,
                discoverPage = 1
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverType(type: String) {
        val catalogs = _uiState.value.discoverCatalogs
        val selectedCatalog = pickDiscoverCatalog(
            catalogs = catalogs,
            selectedType = type,
            preferredKey = _uiState.value.selectedDiscoverCatalogKey
        )
        val selectedGenre: String? = null
        _uiState.update {
            it.copy(
                selectedDiscoverType = type,
                selectedDiscoverCatalogKey = selectedCatalog?.key,
                selectedDiscoverGenre = selectedGenre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverCatalog(catalogKey: String) {
        val catalog = _uiState.value.discoverCatalogs.firstOrNull { it.key == catalogKey } ?: return
        _uiState.update {
            it.copy(
                selectedDiscoverCatalogKey = catalog.key,
                selectedDiscoverType = catalog.type,
                selectedDiscoverGenre = null,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun selectDiscoverGenre(genre: String?) {
        _uiState.update {
            it.copy(
                selectedDiscoverGenre = genre,
                discoverResults = emptyList(),
                pendingDiscoverResults = emptyList(),
                discoverPage = 1,
                discoverHasMore = true
            )
        }
        fetchDiscoverContent(reset = true)
    }

    private fun loadNextDiscoverResults() {
        if (_uiState.value.pendingDiscoverResults.isNotEmpty()) {
            showMoreDiscoverResults()
        } else {
            revealBatchAfterNextDiscoverFetch = true
            loadMoreDiscoverResults()
        }
    }

    private fun showMoreDiscoverResults() {
        val pending = _uiState.value.pendingDiscoverResults
        if (pending.isEmpty()) return
        val nextBatch = pending.take(DISCOVER_SHOW_MORE_BATCH)
        val remaining = pending.drop(DISCOVER_SHOW_MORE_BATCH)
        _uiState.update {
            it.copy(
                discoverResults = it.discoverResults + nextBatch,
                pendingDiscoverResults = remaining
            )
        }
    }

    private fun loadMoreDiscoverResults() {
        val state = _uiState.value
        if (state.query.trim().isNotEmpty()) return
        if (!state.discoverHasMore || state.discoverLoadingMore || state.pendingDiscoverResults.isNotEmpty()) return
        fetchDiscoverContent(reset = false)
    }

    private fun fetchDiscoverContent(reset: Boolean) {
        discoverJob?.cancel()
        discoverJob = viewModelScope.launch {
            val state = _uiState.value
            if (state.query.trim().isNotEmpty()) return@launch
            val selectedCatalog = state.discoverCatalogs.firstOrNull { it.key == state.selectedDiscoverCatalogKey }
                ?: return@launch

            if (reset) {
                revealBatchAfterNextDiscoverFetch = false
                _uiState.update {
                    it.copy(
                        discoverLoading = true,
                        discoverResults = emptyList(),
                        pendingDiscoverResults = emptyList(),
                        discoverPage = 1,
                        discoverHasMore = true
                    )
                }
            } else {
                _uiState.update { it.copy(discoverLoadingMore = true) }
            }

            val currentPage = if (reset) 1 else state.discoverPage + 1
            val skip = if (currentPage <= 1) 0 else (currentPage - 1) * selectedCatalog.skipStep
            val visibleCountBeforeRequest = state.discoverResults.size
            val extraArgs = buildMap<String, String> {
                state.selectedDiscoverGenre?.takeIf { it.isNotBlank() }?.let { put("genre", it) }
            }

            catalogRepository.getCatalog(
                addonBaseUrl = selectedCatalog.addonBaseUrl,
                addonId = selectedCatalog.addonId,
                addonName = selectedCatalog.addonName,
                catalogId = selectedCatalog.catalogId,
                catalogName = selectedCatalog.catalogName,
                type = selectedCatalog.type,
                skip = skip,
                skipStep = selectedCatalog.skipStep,
                extraArgs = extraArgs,
                supportsSkip = selectedCatalog.supportsSkip
            ).collect { result ->
                if (_uiState.value.discoverLocation == DiscoverLocation.OFF) return@collect
                when (result) {
                    is NetworkResult.Success -> {
                        val incoming = result.data.items
                        val existing = if (reset) {
                            emptyList()
                        } else {
                            _uiState.value.discoverResults + _uiState.value.pendingDiscoverResults
                        }
                        val existingKeys = existing.asSequence()
                            .map { "${it.apiType}:${it.id}" }
                            .toSet()
                        val hasNewUniqueIncoming = incoming.any { item ->
                            "${item.apiType}:${item.id}" !in existingKeys
                        }
                        val merged = if (reset) incoming else (existing + incoming)
                        val rawDeduped = merged.distinctBy { "${it.apiType}:${it.id}" }
                        val deduped = if (hideUnreleasedContent) {
                            val today = LocalDate.now()
                            rawDeduped.filterNot { it.isUnreleased(today) }
                        } else {
                            rawDeduped
                        }
                        val shouldRevealBatch = !reset && revealBatchAfterNextDiscoverFetch
                        val visibleLimit = if (reset) {
                            DISCOVER_INITIAL_LIMIT
                        } else if (shouldRevealBatch) {
                            (visibleCountBeforeRequest + DISCOVER_SHOW_MORE_BATCH)
                                .coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        } else {
                            visibleCountBeforeRequest.coerceAtLeast(DISCOVER_INITIAL_LIMIT)
                        }
                        val visible = deduped.take(visibleLimit)
                        val pending = deduped.drop(visibleLimit)
                        val shouldStopPagination = !reset && !hasNewUniqueIncoming
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverResults = visible,
                                pendingDiscoverResults = pending,
                                discoverHasMore = if (shouldStopPagination) false else result.data.hasMore,
                                discoverPage = if (shouldStopPagination) it.discoverPage else currentPage
                            )
                        }
                        revealBatchAfterNextDiscoverFetch = false
                    }
                    is NetworkResult.Error -> {
                        revealBatchAfterNextDiscoverFetch = false
                        _uiState.update {
                            it.copy(
                                discoverLoading = false,
                                discoverLoadingMore = false,
                                discoverHasMore = false
                            )
                        }
                    }
                    NetworkResult.Loading -> Unit
                }
            }
        }
    }

    private fun pickDiscoverCatalog(
        catalogs: List<DiscoverCatalog>,
        selectedType: String,
        preferredKey: String?
    ): DiscoverCatalog? {
        val filtered = catalogs.filter { it.type == selectedType }
        return filtered.firstOrNull { it.key == preferredKey } ?: filtered.firstOrNull()
    }

    private fun buildSearchTargets(addons: List<Addon>): List<Pair<Addon, CatalogDescriptor>> {
        val allSearchTargets = addons.flatMap { addon ->
            addon.catalogs
                .filter { catalog ->
                    catalog.supportsExtra("search")
                }
                .map { catalog -> addon to catalog }
        }

        return allSearchTargets
    }

    private fun catalogKey(addonId: String, type: String, catalogId: String): String {
        return "${addonId}_${type}_$catalogId"
    }

    private fun MetaPreview.toSearchSuggestion(addonBaseUrl: String): SearchSuggestion {
        return SearchSuggestion(
            id = id,
            type = apiType,
            name = name,
            poster = poster,
            year = releaseInfo?.let { YEAR_REGEX.find(it)?.value },
            addonBaseUrl = addonBaseUrl,
            popularity = popularity,
            rating = imdbRating
        )
    }
}

private val YEAR_REGEX = Regex("""(19|20)\d{2}""")
private val DIACRITICS_REGEX = Regex("\\p{Mn}+")

/** Lowercase + strip diacritics so matching is accent-insensitive ("Pokémon" -> "pokemon"). */
private fun normalizeForSearch(s: String): String {
    val decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
    return DIACRITICS_REGEX.replace(decomposed, "").lowercase()
}

/**
 * Relevance bucket, lowest = most relevant. A query word at the title's head
 * (index 0/1) outranks one buried deeper, so a leading article ("The Terminator")
 * doesn't push the match down — without any per-language article list.
 */
private fun relevanceTier(name: String, normQuery: String): Int {
    val n = normalizeForSearch(name)
    if (n == normQuery) return 0
    val wordIdx = n.split(' ', ':', '-', '.', '\'').indexOfFirst { it.startsWith(normQuery) }
    return when {
        n.startsWith(normQuery) || wordIdx in 0..1 -> 1
        wordIdx >= 2 -> 2
        n.contains(normQuery) -> 3
        else -> 4
    }
}
