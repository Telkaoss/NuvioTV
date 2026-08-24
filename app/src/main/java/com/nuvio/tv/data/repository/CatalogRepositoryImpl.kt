package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.mapper.toDomainOrNull
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.repository.CatalogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        forceNetwork: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        emit(NetworkResult.Loading)

        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=$url"
        )

        var lastRaw: okhttp3.Response? = null
        // `max-age=0`, not `no-cache`: the stored entry is treated as stale, so OkHttp still
        // revalidates with its ETag and can settle for a 304. `no-cache` would drop the entry
        // and refetch the whole body.
        val requestCacheControl = if (forceNetwork) "max-age=0" else null
        when (val result = safeApiCall(context) {
            api.getCatalog(url, requestCacheControl).also { lastRaw = it.raw() }
        }) {
            is NetworkResult.Success -> {
                val raw = lastRaw
                // No networkResponse means OkHttp served it from cache; 304 means it revalidated.
                val unchanged = raw != null &&
                    (raw.networkResponse == null || raw.networkResponse?.code == 304)
                val freshUntil = resolveCatalogFreshness(raw)
                val rawItemCount = result.data.metas.size
                val items = result.data.metas
                    .mapNotNull { it?.toDomainOrNull(type, addonBaseUrl) }
                    .distinctBy { it.id }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size} unchanged=$unchanged"
                )

                val catalogRow = CatalogRow(
                    addonId = addonId,
                    addonName = addonName,
                    addonBaseUrl = addonBaseUrl,
                    catalogId = catalogId,
                    catalogName = catalogName,
                    type = ContentType.fromString(type),
                    rawType = type,
                    items = items,
                    isLoading = false,
                    hasMore = supportsSkip && rawItemCount > 0,
                    currentPage = if (skipStep > 0) skip / skipStep else 0,
                    supportsSkip = supportsSkip,
                    skipStep = skipStep,
                    nextSkip = if (supportsSkip && rawItemCount > 0) skip + rawItemCount else skip,
                    extraArgs = extraArgs,
                    notModified = unchanged,
                    freshUntilMs = freshUntil
                )
                emit(NetworkResult.Success(catalogRow))
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message} url=$url"
                )
                emit(result)
            }
            NetworkResult.Loading -> { /* Already emitted */ }
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val queryStart = trimmedBase.indexOf('?')
        val basePath = if (queryStart >= 0) trimmedBase.substring(0, queryStart).trimEnd('/') else trimmedBase
        val baseQuery = if (queryStart >= 0) trimmedBase.substring(queryStart) else ""

        val catalogPath = if (extraArgs.isEmpty()) {
            if (skip > 0) {
                "$basePath/catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "$basePath/catalog/$type/$catalogId.json"
            }
        } else {
            val allArgs = LinkedHashMap<String, String>()
            allArgs.putAll(extraArgs)

            if (!allArgs.containsKey("skip") && skip > 0) {
                allArgs["skip"] = skip.toString()
            }

            val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
                "${encodeArg(key)}=${encodeArg(value)}"
            }

            "$basePath/catalog/$type/$catalogId/$encodedArgs.json"
        }

        return catalogPath + baseQuery
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    /**
     * Instant until which this response can be reused, taken from the addon's own `Cache-Control`.
     * `max-age` counts from when the response was generated, so the `Age` it carries and the time
     * OkHttp has held it are both subtracted.
     */
    private fun resolveCatalogFreshness(raw: okhttp3.Response?): Long {
        if (raw == null) return Long.MAX_VALUE
        val cacheControl = raw.cacheControl
        val maxAgeMs = cacheControl.maxAgeSeconds.takeIf { it >= 0 }?.let { it * 1000L }
            ?: return resolveFreshnessWithoutMaxAge(raw, cacheControl)
        val now = System.currentTimeMillis()
        val apparentAgeMs = raw.headers.getDate("Date")
            ?.let { (raw.receivedResponseAtMillis - it.time).coerceAtLeast(0L) } ?: 0L
        val ageHeaderMs = (raw.headers["Age"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L) * 1000L
        val ageAtReceiptMs = maxOf(apparentAgeMs, ageHeaderMs)
        val heldSinceReceiptMs = (now - raw.receivedResponseAtMillis).coerceAtLeast(0L)
        val remainingMs = (maxAgeMs - ageAtReceiptMs - heldSinceReceiptMs).coerceAtLeast(0L)
        return now + remainingMs
    }

    /**
     * `s-maxage` targets shared caches, and a `Last-Modified` is no promise that the addon
     * honours `If-Modified-Since` (some answer with a full 200), so neither is trusted here.
     */
    private fun resolveFreshnessWithoutMaxAge(
        raw: okhttp3.Response,
        cacheControl: okhttp3.CacheControl
    ): Long {
        if (cacheControl.noCache || cacheControl.noStore) return 0L
        return if (!raw.header("ETag").isNullOrBlank()) 0L else Long.MAX_VALUE
    }

}
