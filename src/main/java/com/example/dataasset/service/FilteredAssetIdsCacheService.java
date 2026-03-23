package com.example.dataasset.service;

import com.example.dataasset.repository.AirJobRepository;
import com.example.dataasset.repository.DataAssetRepository;
import com.example.dataasset.model.DataAsset;
import com.example.dataasset.model.AirJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ehcache.Cache;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.*;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toMap;

/**
 * Caches the filtered dataAssetId list per profileName + tenantId + statusFilter combination.
 *
 * Uses the existing Ehcache pattern from SecretsConfig — direct Cache<K,V> injection
 * with manual get/put, consistent with how TenantService uses tenantConfigCache.
 *
 * No @Cacheable annotation — avoids Spring proxy self-invocation issue entirely.
 * No new dependency — reuses Ehcache already in the project.
 *
 * Cache key : profileName + "_" + tenantId + "_" + sorted status values joined by ","
 * Cache TTL : configured via cache.filteredAssetIds.ttl in application properties (default 60s)
 *
 * Cache miss (page 1)  → runs Query 1 (projection) + Query 2 (air_jobs batched), stores result
 * Cache hit  (page 2+) → returns cached ID list immediately, skips Query 1 + Query 2 entirely
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FilteredAssetIdsCacheService {

    private static final int BATCH_SIZE = 500;  // safe limit for CosmosDB IN clause

    private final DataAssetRepository dataAssetRepository;
    private final AirJobRepository airJobRepository;

    @Qualifier("filteredAssetIdsCache")
    private final Cache<String, List> filteredAssetIdsCache;  // matches existing Cache<K,V> pattern

    public List<String> getFilteredDataAssetIds(
            String profileName,
            String tenantId,
            List<SwaggerJobStatusEnum> statusFilter) {

        String cacheKey = buildCacheKey(profileName, tenantId, statusFilter);

        // Check cache first — same pattern as TenantService.isTenantConfigured()
        List cached = filteredAssetIdsCache.get(cacheKey);
        if (cached != null) {
            log.debug("Cache hit for filtered asset IDs — key: {}", cacheKey);
            return (List<String>) cached;
        }

        log.debug("Cache miss for filtered asset IDs — key: {}, running queries", cacheKey);

        // Cache miss — run the filtering queries
        List<String> result = fetchFilteredAssetIds(profileName, tenantId, statusFilter);

        // Store result in cache — same pattern as TenantService
        filteredAssetIdsCache.put(cacheKey, result);

        return result;
    }

    private List<String> fetchFilteredAssetIds(
            String profileName,
            String tenantId,
            List<SwaggerJobStatusEnum> statusFilter) {

        // Query 1: lightweight projection — only id + lastAIRJobId, not full documents
        List<DataAsset> projections = dataAssetRepository
                .findParentProjectionsByProfileAndTenant(profileName, tenantId);

        if (projections.isEmpty()) {
            return emptyList();
        }

        // Build reverse map: lastAIRJobId -> dataAssetId for lookup after Query 2
        Map<String, String> jobIdToAssetId = projections.stream()
                .filter(p -> p.getLastAIRJobId() != null)
                .collect(toMap(
                        DataAsset::getLastAIRJobId,
                        DataAsset::getId,
                        (existing, duplicate) -> existing));

        if (jobIdToAssetId.isEmpty()) {
            return emptyList();
        }

        // Convert enum to string values for repository query
        List<String> statusValues = statusFilter.stream()
                .map(SwaggerJobStatusEnum::getValue)
                .toList();

        // Query 2: batch air_jobs queries — respects CosmosDB IN clause limits
        List<String> allJobIds = new ArrayList<>(jobIdToAssetId.keySet());
        List<String> matchingAssetIds = new ArrayList<>();

        for (int i = 0; i < allJobIds.size(); i += BATCH_SIZE) {
            List<String> batch = allJobIds.subList(i,
                    Math.min(i + BATCH_SIZE, allJobIds.size()));

            airJobRepository.findByIdInAndStatusIn(batch, statusValues)
                    .stream()
                    .map(job -> jobIdToAssetId.get(job.getId()))
                    .filter(Objects::nonNull)
                    .forEach(matchingAssetIds::add);
        }

        return Collections.unmodifiableList(matchingAssetIds);
    }

    private String buildCacheKey(
            String profileName,
            String tenantId,
            List<SwaggerJobStatusEnum> statusFilter) {

        String statuses = statusFilter.stream()
                .map(SwaggerJobStatusEnum::getValue)
                .sorted()
                .collect(joining(","));

        return profileName + "_" + tenantId + "_" + statuses;
    }
}
