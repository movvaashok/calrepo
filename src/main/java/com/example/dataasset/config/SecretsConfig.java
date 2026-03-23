package com.example.dataasset.config;

import org.ehcache.Cache;
import org.ehcache.config.builders.CacheManagerBuilder;
import org.ehcache.config.builders.CacheConfigurationBuilder;
import org.ehcache.config.builders.ResourcePoolsBuilder;
import org.ehcache.expiry.ExpiryPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.ehcache.config.builders.CacheManagerBuilder.newCacheManagerBuilder;
import static org.ehcache.config.builders.CacheConfigurationBuilder.newCacheConfigurationBuilder;
import static org.ehcache.expiry.ExpiryPolicyBuilder.timeToLiveExpiration;

/**
 * Cache configuration — matches existing SecretsConfig pattern in the codebase.
 *
 * Existing caches (unchanged):
 *   - secrets_cache       : Cache<String, String>
 *   - tenant_config_cache : Cache<String, Boolean>
 *
 * New cache added:
 *   - filteredDataAssetIds : Cache<String, List>
 *     Key   : profileName + "_" + tenantId + "_" + sorted status values
 *     Value : List<String> of matching dataAssetIds
 *     TTL   : cache.filteredAssetIds.ttl (default 60s)
 *     Size  : max 500 unique filter combinations in heap
 */
@Configuration
public class SecretsConfig {

    // -------------------------------------------------------------------------
    // Existing caches — unchanged
    // -------------------------------------------------------------------------

    private static final String SECRETS_CACHE = "secrets_cache";
    private static final String TENANT_CONFIG_CACHE = "tenant_config_cache";

    @Bean("secretCache")
    public Cache<String, String> secretCache(
            @Value("${cache.secrets.ttl}") Duration ttl) {

        return newCacheManagerBuilder()
                .withCache(SECRETS_CACHE,
                        newCacheConfigurationBuilder(
                                String.class, String.class,
                                ResourcePoolsBuilder.heap(100))
                        .withExpiry(timeToLiveExpiration(ttl)))
                .build(true)
                .getCache(SECRETS_CACHE, String.class, String.class);
    }

    @Bean("tenantConfigCache")
    public Cache<String, Boolean> tenantConfigCache(
            @Value("${cache.tenantConfig.ttl}") Duration ttl) {

        return newCacheManagerBuilder()
                .withCache(TENANT_CONFIG_CACHE,
                        newCacheConfigurationBuilder(
                                String.class, Boolean.class,
                                ResourcePoolsBuilder.heap(100))
                        .withExpiry(timeToLiveExpiration(ttl)))
                .build(true)
                .getCache(TENANT_CONFIG_CACHE, String.class, Boolean.class);
    }

    // -------------------------------------------------------------------------
    // New cache — filteredDataAssetIds
    // -------------------------------------------------------------------------

    private static final String FILTERED_ASSET_IDS_CACHE = "filteredDataAssetIds";

    @Bean("filteredAssetIdsCache")
    public Cache<String, List> filteredAssetIdsCache(
            @Value("${cache.filteredAssetIds.ttl:60s}") Duration ttl) {

        return newCacheManagerBuilder()
                .withCache(FILTERED_ASSET_IDS_CACHE,
                        newCacheConfigurationBuilder(
                                String.class, List.class,
                                ResourcePoolsBuilder.heap(500))  // max 500 filter combos
                        .withExpiry(timeToLiveExpiration(ttl)))
                .build(true)
                .getCache(FILTERED_ASSET_IDS_CACHE, String.class, List.class);
    }
}
