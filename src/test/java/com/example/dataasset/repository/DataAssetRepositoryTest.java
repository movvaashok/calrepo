package com.example.dataasset.repository;

import com.example.dataasset.model.DataAsset;
import com.example.dataasset.repository.projection.DataAssetProjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.util.List;

import static com.example.dataasset.util.TestConstants.DEFAULT_TEST_TENANT_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@DataMongoTest
class DataAssetRepositoryTest {

    @Autowired
    DataAssetRepository repository;

    @BeforeEach
    @AfterEach
    public void cleanUp() {
        repository.deleteAll();
    }

    // -------------------------------------------------------------------------
    // findParentProjectionsByProfileAndTenant
    // -------------------------------------------------------------------------

    @Test
    void findParentProjectionsByProfileAndTenant() {
        repository.insert(DataAsset.builder()
                .name("asset-one")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId("job-001")
                .build());
        repository.insert(DataAsset.builder()
                .name("asset-two")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId("job-002")
                .build());
        // different profile — should not be returned
        repository.insert(DataAsset.builder()
                .name("asset-other")
                .profileName("other-profile")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId("job-003")
                .build());

        var results = repository.findParentProjectionsByProfileAndTenant("truata", DEFAULT_TEST_TENANT_ID);

        assertThat(results, hasSize(2));
        assertThat(results, allOf(
                hasItem(hasProperty("lastAIRJobId", equalTo("job-001"))),
                hasItem(hasProperty("lastAIRJobId", equalTo("job-002")))));
    }

    @Test
    void findParentProjectionsByProfileAndTenantExcludesChildAssets() {
        var parent = repository.insert(DataAsset.builder()
                .name("parent-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId("job-parent")
                .build());
        // child asset — has mitigationParentId set, should be excluded
        repository.insert(DataAsset.builder()
                .name("child-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId("job-child")
                .mitigationParentId(parent.getId())
                .build());

        var results = repository.findParentProjectionsByProfileAndTenant("truata", DEFAULT_TEST_TENANT_ID);

        assertThat(results, hasSize(1));
        assertThat(results, hasItem(hasProperty("lastAIRJobId", equalTo("job-parent"))));
    }

    @Test
    void findParentProjectionsByProfileAndTenantReturnsOnlyIdAndLastAIRJobId() {
        repository.insert(DataAsset.builder()
                .name("asset-one")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId("job-001")
                .build());

        List<DataAssetProjection> results = repository
                .findParentProjectionsByProfileAndTenant("truata", DEFAULT_TEST_TENANT_ID);

        assertThat(results, hasSize(1));
        // projection fields are populated
        assertThat(results.get(0).getId(), notNullValue());
        assertThat(results.get(0).getLastAIRJobId(), equalTo("job-001"));
    }

    @Test
    void findParentProjectionsByProfileAndTenantReturnsEmptyWhenNoAssetsExist() {
        var results = repository.findParentProjectionsByProfileAndTenant("truata", DEFAULT_TEST_TENANT_ID);

        assertThat(results, empty());
    }

    // -------------------------------------------------------------------------
    // findByIdIn
    // -------------------------------------------------------------------------

    @Test
    void findByIdIn() {
        var assetOne = repository.insert(DataAsset.builder()
                .name("asset-one")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var assetTwo = repository.insert(DataAsset.builder()
                .name("asset-two")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        // insert a third — not in ID list, should not be returned
        repository.insert(DataAsset.builder()
                .name("asset-three")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        var results = repository.findByIdIn(List.of(assetOne.getId(), assetTwo.getId()));

        assertThat(results, hasSize(2));
        assertThat(results, allOf(
                hasItem(hasProperty("name", equalTo("asset-one"))),
                hasItem(hasProperty("name", equalTo("asset-two")))));
    }

    @Test
    void findByIdInExcludesChildAssets() {
        var parent = repository.insert(DataAsset.builder()
                .name("parent-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var child = repository.insert(DataAsset.builder()
                .name("child-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .mitigationParentId(parent.getId())
                .build());

        // even if child ID is in the list, it should be excluded (has mitigationParentId)
        var results = repository.findByIdIn(List.of(parent.getId(), child.getId()));

        assertThat(results, hasSize(1));
        assertThat(results, hasItem(hasProperty("name", equalTo("parent-asset"))));
    }

    @Test
    void findByIdInReturnsEmptyWhenNoIdsMatch() {
        repository.insert(DataAsset.builder()
                .name("asset-one")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        var results = repository.findByIdIn(List.of("non-existent-id"));

        assertThat(results, empty());
    }

    // -------------------------------------------------------------------------
    // findByProfileNameAndTenantIdAndMitigationParentIdIsNull (paginated)
    // -------------------------------------------------------------------------

    @Test
    void findByProfileNameAndTenantIdAndMitigationParentIdIsNullReturnsPaginatedResults() {
        for (int i = 1; i <= 5; i++) {
            repository.insert(DataAsset.builder()
                    .name("asset-" + i)
                    .profileName("truata")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .build());
        }

        var pageable = PageRequest.of(0, 3, Sort.unsorted());
        Page<DataAsset> page = repository.findByProfileNameAndTenantIdAndMitigationParentIdIsNull(
                "truata", DEFAULT_TEST_TENANT_ID, pageable);

        assertThat(page.getContent(), hasSize(3));
        assertThat(page.getTotalElements(), equalTo(5L));
        assertThat(page.getTotalPages(), equalTo(2));
        assertThat(page.getNumber(), equalTo(0));
    }

    @Test
    void findByProfileNameAndTenantIdAndMitigationParentIdIsNullReturnsSecondPage() {
        for (int i = 1; i <= 5; i++) {
            repository.insert(DataAsset.builder()
                    .name("asset-" + i)
                    .profileName("truata")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .build());
        }

        var pageable = PageRequest.of(1, 3, Sort.unsorted());
        Page<DataAsset> page = repository.findByProfileNameAndTenantIdAndMitigationParentIdIsNull(
                "truata", DEFAULT_TEST_TENANT_ID, pageable);

        assertThat(page.getContent(), hasSize(2));
        assertThat(page.getTotalElements(), equalTo(5L));
        assertThat(page.isLast(), equalTo(true));
    }

    @Test
    void findByProfileNameAndTenantIdAndMitigationParentIdIsNullExcludesChildAssets() {
        var parent = repository.insert(DataAsset.builder()
                .name("parent-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        repository.insert(DataAsset.builder()
                .name("child-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .mitigationParentId(parent.getId())
                .build());

        var pageable = PageRequest.of(0, 20, Sort.unsorted());
        Page<DataAsset> page = repository.findByProfileNameAndTenantIdAndMitigationParentIdIsNull(
                "truata", DEFAULT_TEST_TENANT_ID, pageable);

        assertThat(page.getContent(), hasSize(1));
        assertThat(page.getTotalElements(), equalTo(1L));
        assertThat(page.getContent(), hasItem(hasProperty("name", equalTo("parent-asset"))));
    }

    @Test
    void findByProfileNameAndTenantIdAndMitigationParentIdIsNullReturnsEmptyPageWhenNoData() {
        var pageable = PageRequest.of(0, 20, Sort.unsorted());
        Page<DataAsset> page = repository.findByProfileNameAndTenantIdAndMitigationParentIdIsNull(
                "truata", DEFAULT_TEST_TENANT_ID, pageable);

        assertThat(page.getContent(), empty());
        assertThat(page.getTotalElements(), equalTo(0L));
        assertThat(page.isEmpty(), equalTo(true));
    }

    // -------------------------------------------------------------------------
    // findByMitigationParentIdInAndTenantId
    // -------------------------------------------------------------------------

    @Test
    void findByMitigationParentIdInAndTenantId() {
        var parentOne = repository.insert(DataAsset.builder()
                .name("parent-one")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var parentTwo = repository.insert(DataAsset.builder()
                .name("parent-two")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        repository.insert(DataAsset.builder()
                .name("child-of-one")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .mitigationParentId(parentOne.getId())
                .build());
        repository.insert(DataAsset.builder()
                .name("child-of-two")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .mitigationParentId(parentTwo.getId())
                .build());
        // child of a parent not in the list — should not be returned
        repository.insert(DataAsset.builder()
                .name("child-of-other")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .mitigationParentId("other-parent-id")
                .build());

        var results = repository.findByMitigationParentIdInAndTenantId(
                List.of(parentOne.getId(), parentTwo.getId()), DEFAULT_TEST_TENANT_ID);

        assertThat(results, hasSize(2));
        assertThat(results, allOf(
                hasItem(hasProperty("name", equalTo("child-of-one"))),
                hasItem(hasProperty("name", equalTo("child-of-two")))));
    }

    @Test
    void findByMitigationParentIdInAndTenantIdReturnsEmptyWhenNoChildrenExist() {
        repository.insert(DataAsset.builder()
                .name("parent-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        var results = repository.findByMitigationParentIdInAndTenantId(
                List.of("parent-with-no-children"), DEFAULT_TEST_TENANT_ID);

        assertThat(results, empty());
    }
}
