package com.example.dataasset.repository.projection;

/**
 * Lightweight projection for DataAsset — fetches only id and lastAIRJobId.
 * Used in the status filter path to avoid loading full documents for all 15,000 records.
 */
public interface DataAssetProjection {
    String getId();
    String getLastAIRJobId();
}
