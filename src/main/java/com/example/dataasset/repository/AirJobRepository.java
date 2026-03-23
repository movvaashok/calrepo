package com.example.dataasset.repository;

import com.azure.spring.data.cosmos.repository.CosmosRepository;
import com.azure.spring.data.cosmos.repository.Query;
import com.example.dataasset.model.AirJob;
import com.example.dataasset.repository.projection.AirJobProjection;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AirJobRepository extends CosmosRepository<AirJob, String> {

    /**
     * Query 2 — Filter air_jobs by job IDs and status.
     * Called in batches of 500 to respect CosmosDB IN clause limits.
     * Returns only id + dataAssetId — no full job document loading.
     */
    @Query("SELECT c.id, c.dataAssetId FROM c "
         + "WHERE ARRAY_CONTAINS(@jobIds, c.id) "
         + "AND ARRAY_CONTAINS(@statuses, c.status)")
    List<AirJobProjection> findByIdInAndStatusIn(
            @Param("jobIds") List<String> jobIds,
            @Param("statuses") List<String> statuses);
}
