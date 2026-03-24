package com.example.dataasset.api;

import com.example.dataasset.model.AIRJob;
import com.example.dataasset.model.DataAsset;
import com.example.dataasset.repository.AIRJobRepository;
import com.example.dataasset.repository.DataAssetRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.EnabledIf;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static com.example.dataasset.util.TestConstants.DEFAULT_TEST_TENANT_ID;
import static org.hamcrest.Matchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@EnabledIf(expression = "#{environment.acceptsProfiles('default')}")
final class DataAssetsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    DataAssetRepository assets;

    @Autowired
    AIRJobRepository jobs;

    @Autowired
    ObjectMapper mapper;

    // mock runners/services that would interfere with tests — matches existing pattern
    @MockitoBean
    FileDecryptionRunner decryptionRunner;

    @MockitoBean
    FileDeletionRunner deletionRunner;

    @MockitoBean
    SaaSCleanupService cleanupService;

    @AfterEach
    public void cleanup() {
        assets.deleteAll();
        jobs.deleteAll();
    }

    // -------------------------------------------------------------------------
    // getNestedDataAssetSummariesForProfile
    // -------------------------------------------------------------------------

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileReturnsEmptyPage() throws Exception {
        // no assets inserted — use profileName variable directly, same as existing tests
        var profileName = "truata";

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", profileName))
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("content", empty()),
                        hasProperty("totalElements", equalTo(0L)),
                        hasProperty("totalPages", equalTo(0))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileReturnsDefaultPagination() throws Exception {
        // insert 25 parent assets — default page size is 20, expect first 20 returned
        DataAsset savedAsset = null;
        for (int i = 1; i <= 25; i++) {
            var job = jobs.save(AIRJob.builder()
                    .dataAssetId("asset-" + i)
                    .status("COMPLETED")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .build());
            savedAsset = assets.insert(DataAsset.builder()
                    .name("asset-" + i)
                    .profileName("truata")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .lastAIRJobId(job.getId())
                    .build());
        }

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", savedAsset.getProfileName()))
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("totalElements", equalTo(25L)),
                        hasProperty("totalPages", equalTo(2)),
                        hasProperty("size", equalTo(20)),
                        hasProperty("number", equalTo(0)),
                        hasProperty("first", equalTo(true))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileWithPagination() throws Exception {
        DataAsset savedAsset = null;
        for (int i = 1; i <= 5; i++) {
            savedAsset = assets.insert(DataAsset.builder()
                    .name("asset-" + i)
                    .profileName("truata")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .build());
        }

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", savedAsset.getProfileName()))
                .param("pageable.page", "0")
                .param("pageable.size", "3")
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("totalElements", equalTo(5L)),
                        hasProperty("totalPages", equalTo(2)),
                        hasProperty("size", equalTo(3)),
                        hasProperty("number", equalTo(0)),
                        hasProperty("numberOfElements", equalTo(3))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileReturnsSecondPage() throws Exception {
        DataAsset savedAsset = null;
        for (int i = 1; i <= 5; i++) {
            savedAsset = assets.insert(DataAsset.builder()
                    .name("asset-" + i)
                    .profileName("truata")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .build());
        }

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", savedAsset.getProfileName()))
                .param("pageable.page", "1")
                .param("pageable.size", "3")
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("totalElements", equalTo(5L)),
                        hasProperty("numberOfElements", equalTo(2)),
                        hasProperty("last", equalTo(true))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileWithSingleStatusFilter() throws Exception {
        // completed job
        var completedJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-completed")
                .status("COMPLETED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        assets.insert(DataAsset.builder()
                .name("completed-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(completedJob.getId())
                .build());

        // failed job — should not appear in COMPLETED filter
        var failedJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-failed")
                .status("FAILED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var failedAsset = assets.insert(DataAsset.builder()
                .name("failed-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(failedJob.getId())
                .build());

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", failedAsset.getProfileName()))
                .param("status", "COMPLETED")
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("totalElements", equalTo(1L)),
                        hasProperty("content", hasItem(
                                hasProperty("name", equalTo("completed-asset"))))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileWithMultipleStatusFilters() throws Exception {
        var failedJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-failed")
                .status("FAILED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        assets.insert(DataAsset.builder()
                .name("failed-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(failedJob.getId())
                .build());

        var cancelledJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-cancelled")
                .status("CANCELLED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        assets.insert(DataAsset.builder()
                .name("cancelled-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(cancelledJob.getId())
                .build());

        // running — should not appear
        var runningJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-running")
                .status("RUNNING")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var runningAsset = assets.insert(DataAsset.builder()
                .name("running-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(runningJob.getId())
                .build());

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", runningAsset.getProfileName()))
                .param("status", "FAILED")
                .param("status", "CANCELLED")
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        var page = mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class);

        assertThat(page.getTotalElements(), equalTo(2L));
        assertThat(page.getContent(), allOf(
                hasItem(hasProperty("name", equalTo("failed-asset"))),
                hasItem(hasProperty("name", equalTo("cancelled-asset"))),
                not(hasItem(hasProperty("name", equalTo("running-asset"))))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileWithStatusAndPagination() throws Exception {
        for (int i = 1; i <= 5; i++) {
            var job = jobs.save(AIRJob.builder()
                    .dataAssetId("asset-completed-" + i)
                    .status("COMPLETED")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .build());
            assets.insert(DataAsset.builder()
                    .name("completed-asset-" + i)
                    .profileName("truata")
                    .tenantId(DEFAULT_TEST_TENANT_ID)
                    .lastAIRJobId(job.getId())
                    .build());
        }
        // failed asset — should not appear in COMPLETED filter
        var failedJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-failed")
                .status("FAILED")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var failedAsset = assets.insert(DataAsset.builder()
                .name("failed-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(failedJob.getId())
                .build());

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", failedAsset.getProfileName()))
                .param("status", "COMPLETED")
                .param("pageable.page", "0")
                .param("pageable.size", "3")
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("totalElements", equalTo(5L)),   // only COMPLETED count
                        hasProperty("totalPages", equalTo(2)),
                        hasProperty("numberOfElements", equalTo(3)),
                        hasProperty("number", equalTo(0))));
    }

    @Test
    @CalibratUser(privileges = GET_DATA_ASSET)
    void getNestedDataAssetSummariesForProfileWithStatusFilterReturnsEmptyPageWhenNoMatch() throws Exception {
        var runningJob = jobs.save(AIRJob.builder()
                .dataAssetId("asset-running")
                .status("RUNNING")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .build());
        var runningAsset = assets.insert(DataAsset.builder()
                .name("running-asset")
                .profileName("truata")
                .tenantId(DEFAULT_TEST_TENANT_ID)
                .lastAIRJobId(runningJob.getId())
                .build());

        MockHttpServletRequestBuilder req = get(urlTemplate("/data-assets/nested-summaries/{profileName}", runningAsset.getProfileName()))
                .param("status", "COMPLETED")
                .contentType(APPLICATION_JSON);

        String response = mockMvc.perform(req)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(mapper.readValue(response, SwaggerDataAssetRiskAssessmentSummaryPage.class),
                allOf(
                        hasProperty("content", empty()),
                        hasProperty("totalElements", equalTo(0L)),
                        hasProperty("empty", equalTo(true))));
    }
}
