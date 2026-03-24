package com.example.dataasset.repository;

import com.example.dataasset.model.AIRJob;
import com.example.dataasset.repository.projection.AirJobProjection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;

import java.util.List;

import static com.example.dataasset.util.TestConstants.DEFAULT_TEST_TENANT_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@DataMongoTest
class AIRJobRepositoryTest {

    @Autowired
    AIRJobRepository jobs;

    @AfterEach
    public void teardown() {
        jobs.deleteAll();
    }

    // -------------------------------------------------------------------------
    // Existing tests (unchanged)
    // -------------------------------------------------------------------------

    @Test
    void testFindById() {
        var job = jobs.save(AIRJob.builder().dataAssetId("123").build());
        assertThat(jobs.findById(job.getId()), isPresentAnd(hasProperty("dataAssetId", equalTo("123"))));
    }

    @Test
    void testFindAllByDataAssetId() {
        var dataAssetIdOne = "123";
        var dataAssetIdTwo = "456";

        jobs.saveAll(List.of(
                AIRJob.builder().dataAssetId(dataAssetIdOne).tenantId(DEFAULT_TEST_TENANT_ID).build(),
                AIRJob.builder().dataAssetId(dataAssetIdOne).tenantId(DEFAULT_TEST_TENANT_ID).build(),
                AIRJob.builder().dataAssetId(dataAssetIdOne).tenantId(DEFAULT_TEST_TENANT_ID).build(),
                AIRJob.builder().dataAssetId(dataAssetIdTwo).tenantId(DEFAULT_TEST_TENANT_ID).build(),
                AIRJob.builder().dataAssetId(dataAssetIdTwo).tenantId(DEFAULT_TEST_TENANT_ID).build()));

        assertThat(jobs.findAllByDataAssetIdAndTenantId(dataAssetIdOne, DEFAULT_TEST_TENANT_ID).toList(), hasSize(3));
        assertThat(jobs.findAllByDataAssetIdAndTenantId(dataAssetIdTwo, DEFAULT_TEST_TENANT_ID).toList(), hasSize(2));
    }

    // -------------------------------------------------------------------------
    // findByIdInAndStatusIn
    // -------------------------------------------------------------------------

    @Test
    void testFindByIdInAndStatusInReturnsMatchingJobs() {
        var jobOne = jobs.save(AIRJob.builder()
                .dataAssetId("asset-001")
                .status("COMPLETED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var jobTwo = jobs.save(AIRJob.builder()
                .dataAssetId("asset-002")
                .status("COMPLETED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        // different status — should not be returned
        jobs.save(AIRJob.builder()
                .dataAssetId("asset-003")
                .status("RUNNING")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        List<AirJobProjection> results = jobs.findByIdInAndStatusIn(
                List.of(jobOne.getId(), jobTwo.getId()),
                List.of("COMPLETED"));

        assertThat(results, hasSize(2));
        assertThat(results, allOf(
                hasItem(hasProperty("dataAssetId", equalTo("asset-001"))),
                hasItem(hasProperty("dataAssetId", equalTo("asset-002")))));
    }

    @Test
    void testFindByIdInAndStatusInReturnsEmptyWhenNoStatusMatches() {
        var job = jobs.save(AIRJob.builder()
                .dataAssetId("asset-001")
                .status("RUNNING")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        List<AirJobProjection> results = jobs.findByIdInAndStatusIn(
                List.of(job.getId()),
                List.of("COMPLETED"));

        assertThat(results, empty());
    }

    @Test
    void testFindByIdInAndStatusInWithMultipleStatuses() {
        var jobFailed = jobs.save(AIRJob.builder()
                .dataAssetId("asset-001")
                .status("FAILED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var jobCancelled = jobs.save(AIRJob.builder()
                .dataAssetId("asset-002")
                .status("CANCELLED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        // running — should not be returned
        var jobRunning = jobs.save(AIRJob.builder()
                .dataAssetId("asset-003")
                .status("RUNNING")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        List<AirJobProjection> results = jobs.findByIdInAndStatusIn(
                List.of(jobFailed.getId(), jobCancelled.getId(), jobRunning.getId()),
                List.of("FAILED", "CANCELLED"));

        assertThat(results, hasSize(2));
        assertThat(results, allOf(
                hasItem(hasProperty("dataAssetId", equalTo("asset-001"))),
                hasItem(hasProperty("dataAssetId", equalTo("asset-002")))));
        assertThat(results, not(hasItem(hasProperty("dataAssetId", equalTo("asset-003")))));
    }

    @Test
    void testFindByIdInAndStatusInReturnsOnlyProjectionFields() {
        var job = jobs.save(AIRJob.builder()
                .dataAssetId("asset-001")
                .status("COMPLETED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        List<AirJobProjection> results = jobs.findByIdInAndStatusIn(
                List.of(job.getId()),
                List.of("COMPLETED"));

        assertThat(results, hasSize(1));
        // projection contains id and dataAssetId
        assertThat(results.get(0).getId(), notNullValue());
        assertThat(results.get(0).getDataAssetId(), equalTo("asset-001"));
    }

    @Test
    void testFindByIdInAndStatusInReturnsEmptyWhenJobIdNotInList() {
        jobs.save(AIRJob.builder()
                .dataAssetId("asset-001")
                .status("COMPLETED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        // pass a non-existent job ID
        List<AirJobProjection> results = jobs.findByIdInAndStatusIn(
                List.of("non-existent-job-id"),
                List.of("COMPLETED"));

        assertThat(results, empty());
    }

    @Test
    void testFindByIdInAndStatusInHandlesSingleStatus() {
        var job = jobs.save(AIRJob.builder()
                .dataAssetId("asset-001")
                .status("COMPLETED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());

        List<AirJobProjection> results = jobs.findByIdInAndStatusIn(
                List.of(job.getId()),
                List.of("COMPLETED"));

        assertThat(results, hasSize(1));
        assertThat(results, hasItem(hasProperty("dataAssetId", equalTo("asset-001"))));
    }
}
