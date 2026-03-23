package com.example.dataasset.service;

import com.example.dataasset.repository.AirJobRepository;
import com.example.dataasset.repository.DataAssetRepository;
import com.example.dataasset.repository.projection.AirJobProjection;
import com.example.dataasset.repository.projection.DataAssetProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;

import static com.example.dataasset.config.CacheConfig.FILTERED_ASSET_IDS_CACHE;
import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.toMap;

/**
 * Separate service for the cache layer.
 *
 * IMPORTANT: Must be a separate Spring bean from DataAssetsService.
 * Spring Cache @Cacheable only intercepts calls via the Spring proxy —
 * it will NOT work if called from within the same class (self-invocation).
 *
 * Cache key  : profileName + tenantId + statusFilter (sorted for consistency)
 * Cache value: ordered list of dataAssetIds matching the status filter
 * TTL        : 60 seconds — covers a typical pagination session
 *
 * First request  → runs Query 1 (projection) + Query 2 (air_jobs filter), caches result
 * Page 2, 3, 4.. → hits cache, skips Query 1 and Query 2 entirely
 */
@Service
@RequiredArgsConstructor
public class FilteredAssetIdsCacheService {

    // Safe batch size for CosmosDB IN clause
    private static final int BATCH_SIZE = 500;

    private final DataAssetRepository dataAssetRepository;
    private final AirJobRepository airJobRepository;

    @Cacheable(
        value  = FILTERED_ASSET_IDS_CACHE,
        key    = "#profileName + '_' + #tenantId + '_' + T(java.util.Arrays).toString(#statusFilter.stream().sorted().toArray())",
        unless = "#result.isEmpty()"
    )
    public List<String> getFilteredDataAssetIds(
            String profileName,
            String tenantId,
            List<String> statusFilter) {

        // Query 1: lightweight projection — only id + lastAIRJobId, not full documents
        List<DataAssetProjection> projections = dataAssetRepository
                .findParentProjectionsByProfileAndTenant(profileName, tenantId);

        if (projections.isEmpty()) {
            return emptyList();
        }

        // Build reverse map: lastAIRJobId -> dataAssetId for lookup after Query 2
        Map<String, String> jobIdToAssetId = projections.stream()
                .filter(p -> p.getLastAIRJobId() != null)
                .collect(toMap(
                        DataAssetProjection::getLastAIRJobId,
                        DataAssetProjection::getId,
                        (existing, duplicate) -> existing  // keep first if duplicate job IDs
                ));

        if (jobIdToAssetId.isEmpty()) {
            return emptyList();
        }

        // Query 2: batch air_jobs queries — respects CosmosDB IN clause limits
        List<String> allJobIds = new ArrayList<>(jobIdToAssetId.keySet());
        List<String> matchingAssetIds = new ArrayList<>();

        for (int i = 0; i < allJobIds.size(); i += BATCH_SIZE) {
            List<String> batch = allJobIds.subList(i, Math.min(i + BATCH_SIZE, allJobIds.size()));

            List<AirJobProjection> matchingJobs = airJobRepository
                    .findByIdInAndStatusIn(batch, statusFilter);

            matchingJobs.stream()
                    .map(job -> jobIdToAssetId.get(job.getId()))
                    .filter(Objects::nonNull)
                    .forEach(matchingAssetIds::add);
        }

        return Collections.unmodifiableList(matchingAssetIds);
    }
}
