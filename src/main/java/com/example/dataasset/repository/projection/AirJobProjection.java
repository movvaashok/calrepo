package com.example.dataasset.repository.projection;

/**
 * Lightweight projection for AirJob — fetches only id and dataAssetId.
 * Used in the status filter path to avoid loading full job documents.
 */
public interface AirJobProjection {
    String getId();
    String getDataAssetId();
}
