package com.example.dataasset.repository;

import com.azure.spring.data.cosmos.repository.CosmosRepository;
import com.azure.spring.data.cosmos.repository.Query;
import com.example.dataasset.model.DataAsset;
import com.example.dataasset.repository.projection.DataAssetProjection;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DataAssetRepository extends CosmosRepository<DataAsset, String> {

    /**
     * Query 1 — Lightweight projection.
     * Fetches only id + lastAIRJobId for all parent assets of a profile.
     * Does NOT load full documents — avoids memory overhead for 15,000 records.
     */
    @Query("SELECT c.id, c.lastAIRJobId FROM c "
         + "WHERE c.profileName = @profileName "
         + "AND c.tenantId = @tenantId "
         + "AND IS_NULL(c.mitigationParentId)")
    List<DataAssetProjection> findParentProjectionsByProfileAndTenant(
            @Param("profileName") String profileName,
            @Param("tenantId") String tenantId);

    /**
     * Query 3 — Fetch full documents for a specific page's IDs only.
     * Called after cache lookup — loads at most `pageSize` (e.g. 20) full documents.
     */
    @Query("SELECT * FROM c "
         + "WHERE ARRAY_CONTAINS(@ids, c.id) "
         + "AND IS_NULL(c.mitigationParentId)")
    List<DataAsset> findByIdIn(@Param("ids") List<String> ids);

    /**
     * No status filter path — DB handles pagination directly, accurate counts.
     */
    Page<DataAsset> findByProfileNameAndTenantIdAndMitigationParentIdIsNull(
            String profileName, String tenantId, Pageable pageable);

    /**
     * Query 4 — Fetch mitigated children scoped to current page's parent IDs only.
     * Never fetches all tenant children — only children for the 20 parents on this page.
     */
    @Query("SELECT * FROM c "
         + "WHERE ARRAY_CONTAINS(@parentIds, c.mitigationParentId) "
         + "AND c.tenantId = @tenantId")
    List<DataAsset> findByMitigationParentIdInAndTenantId(
            @Param("parentIds") List<String> parentIds,
            @Param("tenantId") String tenantId);
}
