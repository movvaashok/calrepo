package com.example.dataasset.service;

import com.example.dataasset.repository.DataAssetRepository;
import com.example.dataasset.model.DataAsset;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static java.util.Collections.emptyList;
import static java.util.stream.Collectors.groupingBy;

/**
 * Updated findRiskAssessmentDataAssetSummaries — supports status filter + pagination.
 *
 * Two paths:
 *
 *   PATH 1 (no status filter):
 *     DB handles pagination directly — accurate totalElements/totalPages, 2 queries per page.
 *
 *   PATH 2 (status filter present):
 *     - Page 1: FilteredAssetIdsCacheService runs Query 1 (projection) + Query 2 (air_jobs),
 *               caches the filtered dataAssetId list for 60 seconds.
 *     - Page 2+: cache hit — skips Query 1 and Query 2 entirely.
 *     - Always: fetches full documents only for the current page (20 records), never all 15,000.
 *
 * Query count per request:
 *   No filter  → 2 queries (paginated parents + scoped children)
 *   Filter p1  → 4 queries (projection + air_jobs batched + page docs + scoped children)
 *   Filter p2+ → 2 queries (page docs + scoped children) — cache handles the rest
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataAssetsService {

    private static final String PRIVILEGE_GET_DATA_ASSET = "hasAuthority('GET_DATA_ASSET')";

    private final DataAssetRepository assets;
    private final FilteredAssetIdsCacheService filteredAssetIdsCacheService;
    private final CurrentUser currentUser;

    @PreAuthorize(PRIVILEGE_GET_DATA_ASSET)
    public SwaggerDataAssetRiskAssessmentSummaryPage findRiskAssessmentDataAssetSummaries(
            String profileName,
            List<String> statusFilter,
            SwaggerPageable pageable) {

        Pageable pageRequest = fromSwagger(pageable, "created");
        boolean hasStatusFilter = statusFilter != null && !statusFilter.isEmpty();

        return hasStatusFilter
                ? findWithStatusFilter(profileName, statusFilter, pageRequest)
                : findWithoutStatusFilter(profileName, pageRequest);
    }

    // -------------------------------------------------------------------------
    // PATH 1 — No status filter
    // DB handles filtering + pagination — totalElements/totalPages always accurate
    // -------------------------------------------------------------------------
    private SwaggerDataAssetRiskAssessmentSummaryPage findWithoutStatusFilter(
            String profileName, Pageable pageRequest) {

        Page<DataAsset> parentPage = assets
                .findByProfileNameAndTenantIdAndMitigationParentIdIsNull(
                        profileName, currentUser.getTenantId(), pageRequest);

        if (parentPage.isEmpty()) {
            return emptyPage(pageRequest);
        }

        List<String> parentIds = parentPage.getContent().stream()
                .map(DataAsset::getId)
                .toList();

        // Fetch children scoped to this page's parents only — not all tenant children
        Map<String, List<DataAsset>> mitigatedByParent = assets
                .findByMitigationParentIdInAndTenantId(parentIds, currentUser.getTenantId())
                .stream()
                .collect(groupingBy(DataAsset::getMitigationParentId));

        List<SwaggerDataAssetRiskAssessmentSummary> summaries = parentPage.getContent().stream()
                .map(parent -> buildRiskAssessmentSummary(
                        parent,
                        mitigatedByParent.getOrDefault(parent.getId(), emptyList())))
                .toList();

        return new SwaggerDataAssetRiskAssessmentSummaryPage()
                .content(summaries)
                .totalElements(parentPage.getTotalElements())   // accurate — no filter applied
                .totalPages(parentPage.getTotalPages())
                .size(parentPage.getSize())
                .number(parentPage.getNumber())
                .first(parentPage.isFirst())
                .last(parentPage.isLast())
                .numberOfElements(parentPage.getNumberOfElements())
                .empty(parentPage.isEmpty());
    }

    // -------------------------------------------------------------------------
    // PATH 2 — Status filter present
    // Cache does the heavy lifting — page 2, 3, 4 skip Query 1 + Query 2 entirely
    // -------------------------------------------------------------------------
    private SwaggerDataAssetRiskAssessmentSummaryPage findWithStatusFilter(
            String profileName, List<String> statusFilter, Pageable pageRequest) {

        // Page 1 → runs projection + air_jobs queries, caches filtered ID list
        // Page 2+ → hits cache, returns immediately without any filtering queries
        List<String> filteredIds = filteredAssetIdsCacheService.getFilteredDataAssetIds(
                profileName, currentUser.getTenantId(), statusFilter);

        if (filteredIds.isEmpty()) {
            return emptyPage(pageRequest);
        }

        // Slice the cached ID list for the requested page number
        int page          = pageRequest.getPageNumber();
        int size          = pageRequest.getPageSize();
        int totalFiltered = filteredIds.size();
        int totalPages    = (int) Math.ceil((double) totalFiltered / size);
        int fromIndex     = page * size;

        if (fromIndex >= totalFiltered) {
            return emptyPage(pageRequest);
        }

        List<String> pageIds = filteredIds.subList(fromIndex, Math.min(fromIndex + size, totalFiltered));

        // Query 3: fetch full documents only for this page's IDs — at most `pageSize` documents
        List<DataAsset> parentAssets = assets.findByIdIn(pageIds);

        // Query 4: fetch children scoped to this page's parents only
        Map<String, List<DataAsset>> mitigatedByParent = assets
                .findByMitigationParentIdInAndTenantId(pageIds, currentUser.getTenantId())
                .stream()
                .collect(groupingBy(DataAsset::getMitigationParentId));

        List<SwaggerDataAssetRiskAssessmentSummary> summaries = parentAssets.stream()
                .map(parent -> buildRiskAssessmentSummary(
                        parent,
                        mitigatedByParent.getOrDefault(parent.getId(), emptyList())))
                .toList();

        return new SwaggerDataAssetRiskAssessmentSummaryPage()
                .content(summaries)
                .totalElements((long) totalFiltered)     // accurate — reflects filtered count
                .totalPages(totalPages)
                .size(size)
                .number(page)
                .first(page == 0)
                .last(page >= totalPages - 1)
                .numberOfElements(summaries.size())
                .empty(summaries.isEmpty());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private SwaggerDataAssetRiskAssessmentSummaryPage emptyPage(Pageable pageRequest) {
        return new SwaggerDataAssetRiskAssessmentSummaryPage()
                .content(emptyList())
                .totalElements(0L)
                .totalPages(0)
                .size(pageRequest.getPageSize())
                .number(pageRequest.getPageNumber())
                .first(true).last(true)
                .numberOfElements(0).empty(true);
    }

    private Pageable fromSwagger(SwaggerPageable pageable, String... sortField) {
        if (pageable == null) {
            return PageRequest.of(0, 20, Sort.by(DESC, sortField));
        }

        Sort sort;
        if (pageable.getSort() != null) {
            SwaggerSort s = pageable.getSort();
            sort = Sort.by(
                    s.getDirection() == SwaggerSort.DirectionEnum.ASC ? ASC : DESC,
                    s.getProperties().toArray(new String[0]));
        } else {
            sort = Sort.by(DESC, sortField);
        }

        return PageRequest.of(pageable.getPage(), pageable.getSize(), sort);
    }
}
