# Implementation Plan: Thymeleaf-Based PDF Generation for Safe Data

## Table of Contents
1. [Project Overview](#project-overview)
2. [Phase 1: Setup & Infrastructure](#phase-1-setup--infrastructure)
3. [Phase 2: Core Service Implementation](#phase-2-core-service-implementation)
4. [Phase 3: REST Endpoint & Integration](#phase-3-rest-endpoint--integration)
5. [Phase 4: RabbitMQ Integration](#phase-4-rabbitmq-integration)
6. [Phase 5: Testing & Optimization](#phase-5-testing--optimization)
7. [Deployment & Rollout](#deployment--rollout)
8. [Support & Monitoring](#support--monitoring)

---

## Project Overview

### Objectives
1. Move PDF generation from UI to backend service
2. Implement generic PDF generation service callable from REST endpoint and RabbitMQ listener
3. Support flexible template-based PDF generation using Thymeleaf
4. Achieve <45 second PDF generation SLA
5. Enable PDF attachment to job completion emails

### Technology Stack
- **Java Version**: 25
- **Spring Boot**: 3.10
- **Template Engine**: Thymeleaf 3.1.x
- **HTML to PDF**: Flying Saucer (OpenHTML2PDF) + iText 7
- **Message Queue**: RabbitMQ
- **Database**: Existing database (assumed PostgreSQL/MySQL)

### Timeline
- **Phase 1**: 2-3 days
- **Phase 2**: 4-5 days
- **Phase 3**: 3-4 days
- **Phase 4**: 2-3 days
- **Phase 5**: 5-7 days
- **Total**: 16-22 days

---

## Phase 1: Setup & Infrastructure

### Task 1.1: Add Maven Dependencies

**File**: `pom.xml`

```xml
<!-- Thymeleaf Template Engine -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>

<!-- Flying Saucer for HTML to PDF conversion -->
<dependency>
    <groupId>org.xhtmlrenderer</groupId>
    <artifactId>core-renderer</artifactId>
    <version>9.7.2</version>
</dependency>

<!-- iText 7 (for advanced PDF features) -->
<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itext-core</artifactId>
    <version>8.0.4</version>
</dependency>

<!-- Lombok for reducing boilerplate -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- SLF4J/Logback for logging (usually already present) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-logging</artifactId>
</dependency>
```

**Rationale:**
- Thymeleaf: Official Spring Boot integration, native template engine
- Flying Saucer: Pure Java HTML to PDF with excellent CSS support
- iText 7: PDF manipulation (pulled transitively)
- Lombok: Reduces boilerplate code

**Duration**: 1 hour

---

### Task 1.2: Create Package Structure

**Create the following packages:**

```
src/main/java/com/safedata/
├── pdf/
│   ├── controller/          # REST endpoints
│   ├── service/             # Business logic
│   ├── converter/           # HTML to PDF conversion
│   ├── config/              # Configuration classes
│   ├── dto/                 # Data transfer objects
│   ├── exception/           # Custom exceptions
│   └── util/                # Utility classes
├── fingerprint/
│   ├── event/               # Domain events
│   └── listener/            # RabbitMQ listeners
└── email/
    └── service/             # Email service integration
```

**Duration**: 30 minutes

---

### Task 1.3: Create Configuration File (application.yaml)

**File**: `src/main/resources/application.yaml`

```yaml
spring:
  application:
    name: safe-data
  thymeleaf:
    prefix: classpath:/templates/
    suffix: .html
    mode: HTML
    encoding: UTF-8
    cache: true
    servlet:
      content-type: text/html

pdf:
  generation:
    enabled: true
    max-execution-time-ms: 45000  # 45 seconds SLA
    naming-pattern: "fingerprint-report-{jobId}-{timestamp}.pdf"
    templates:
      fingerprint-job: "pdf/fingerprint-report"  # Template path without .html
    storage:
      strategy: "on-the-fly"  # on-the-fly or datalake
      datalake:
        enabled: false
        retention-days: 0
        path: "s3://safe-data-datalake/pdf-reports/"
    converter:
      type: "flying-saucer"  # Flying Saucer implementation
      timeout-ms: 30000
```

**Duration**: 1 hour

---

### Task 1.4: Create Template Directory Structure

**Create directory structure:**

```
src/main/resources/templates/
└── pdf/
    └── fingerprint-report.html
```

**Duration**: 30 minutes

---

## Phase 2: Core Service Implementation

### Task 2.1: Create Exception Handling

**File**: `src/main/java/com/safedata/pdf/exception/PdfGenerationException.java`

```java
package com.safedata.pdf.exception;

public class PdfGenerationException extends RuntimeException {
    
    private static final long serialVersionUID = 1L;

    public PdfGenerationException(String message) {
        super(message);
    }

    public PdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }

    public PdfGenerationException(String message, Throwable cause, 
                                   boolean enableSuppression, 
                                   boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
```

**Duration**: 30 minutes

---

### Task 2.2: Create DTOs (Data Transfer Objects)

**File**: `src/main/java/com/safedata/pdf/dto/PdfResponse.java`

```java
package com.safedata.pdf.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfResponse {
    
    @JsonProperty("pdf_bytes")
    private byte[] pdfBytes;
    
    @JsonProperty("content_type")
    private String contentType;
    
    @JsonProperty("filename")
    private String filename;
    
    @JsonProperty("error_message")
    private String errorMessage;
    
    @JsonProperty("success")
    private Boolean success;

    public static PdfResponse success(byte[] pdfBytes, String contentType, String filename) {
        return PdfResponse.builder()
            .pdfBytes(pdfBytes)
            .contentType(contentType)
            .filename(filename)
            .success(true)
            .build();
    }

    public static PdfResponse error(String errorMessage) {
        return PdfResponse.builder()
            .errorMessage(errorMessage)
            .success(false)
            .build();
    }
}
```

**Duration**: 1 hour

---

### Task 2.3: Create Configuration Classes

**File**: `src/main/java/com/safedata/pdf/config/PdfConfiguration.java`

```java
package com.safedata.pdf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Data;
import java.util.HashMap;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "pdf.generation")
@Data
public class PdfConfiguration {
    
    private boolean enabled;
    private long maxExecutionTimeMs;
    private String namingPattern;
    private Map<String, String> templates = new HashMap<>();
    private StorageConfig storage = new StorageConfig();
    private ConverterConfig converter = new ConverterConfig();

    public String getTemplateName(String key) {
        return templates.getOrDefault(key, "pdf/fingerprint-report");
    }

    @Data
    public static class StorageConfig {
        private String strategy;  // on-the-fly or datalake
        private DataLakeConfig datalake = new DataLakeConfig();
    }

    @Data
    public static class DataLakeConfig {
        private boolean enabled;
        private int retentionDays;
        private String path;
    }

    @Data
    public static class ConverterConfig {
        private String type;
        private long timeoutMs;
    }
}
```

**Duration**: 1.5 hours

---

### Task 2.4: Create HTML to PDF Converter Interface

**File**: `src/main/java/com/safedata/pdf/converter/HtmlToPdfConverter.java`

```java
package com.safedata.pdf.converter;

import com.safedata.pdf.exception.PdfGenerationException;

public interface HtmlToPdfConverter {
    /**
     * Converts HTML content to PDF bytes
     * 
     * @param htmlContent HTML content as string
     * @return PDF as byte array
     * @throws PdfGenerationException if conversion fails
     */
    byte[] convertHtmlToPdf(String htmlContent) throws PdfGenerationException;
}
```

**Duration**: 30 minutes

---

### Task 2.5: Implement Flying Saucer PDF Converter

**File**: `src/main/java/com/safedata/pdf/converter/FlyingSaucerHtmlToPdfConverter.java`

```java
package com.safedata.pdf.converter;

import com.lowagie.text.DocumentException;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.springframework.stereotype.Component;
import com.safedata.pdf.exception.PdfGenerationException;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;

@Component
@Slf4j
public class FlyingSaucerHtmlToPdfConverter implements HtmlToPdfConverter {

    @Override
    public byte[] convertHtmlToPdf(String htmlContent) throws PdfGenerationException {
        long startTime = System.currentTimeMillis();
        try {
            log.debug("Starting HTML to PDF conversion");
            
            ITextRenderer renderer = new ITextRenderer();
            
            // Set up base URL for resource resolution
            String baseUrl = "file:///";
            renderer.setDocumentFromString(htmlContent, baseUrl);
            
            // Layout the document
            log.debug("Laying out document");
            renderer.layout();
            
            // Render to PDF in memory
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            renderer.createPDF(outputStream);
            
            byte[] pdfBytes = outputStream.toByteArray();
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("PDF conversion completed successfully. Size: {} bytes, Duration: {}ms", 
                     pdfBytes.length, duration);
            
            return pdfBytes;
            
        } catch (DocumentException e) {
            log.error("PDF rendering error during conversion", e);
            throw new PdfGenerationException("Unable to create document: Rendering failed", e);
        } catch (Exception e) {
            log.error("Unexpected error during PDF conversion", e);
            throw new PdfGenerationException("Unable to create document: Unexpected error", e);
        }
    }
}
```

**Duration**: 2 hours (includes performance optimization)

---

### Task 2.6: Create PDF Generation Service Interface

**File**: `src/main/java/com/safedata/pdf/service/PdfGenerationService.java`

```java
package com.safedata.pdf.service;

import com.safedata.pdf.exception.PdfGenerationException;
import com.safedata.fingerprint.entity.FingerPrintJob;
import com.safedata.fingerprint.entity.FingerPrintReport;
import java.util.UUID;

public interface PdfGenerationService {
    
    /**
     * Generate PDF from job ID (fetches job and report from database)
     * 
     * @param jobId Fingerprint job ID
     * @return PDF as byte array
     * @throws PdfGenerationException if generation fails
     */
    byte[] generateFingerPrintPdf(UUID jobId) throws PdfGenerationException;

    /**
     * Generate PDF from job and report objects
     * Useful for testing and RabbitMQ listener scenarios
     * 
     * @param job FingerPrintJob entity
     * @param report FingerPrintReport entity
     * @return PDF as byte array
     * @throws PdfGenerationException if generation fails
     */
    byte[] generateFingerPrintPdf(FingerPrintJob job, FingerPrintReport report) 
        throws PdfGenerationException;

    /**
     * Generate PDF filename based on configuration pattern
     * 
     * @param jobId Job ID
     * @return Filename string
     */
    String generatePdfFilename(UUID jobId);
}
```

**Duration**: 30 minutes

---

### Task 2.7: Implement PDF Generation Service

**File**: `src/main/java/com/safedata/pdf/service/FingerPrintPdfGenerationService.java`

```java
package com.safedata.pdf.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import com.safedata.pdf.config.PdfConfiguration;
import com.safedata.pdf.converter.HtmlToPdfConverter;
import com.safedata.pdf.exception.PdfGenerationException;
import com.safedata.fingerprint.entity.FingerPrintJob;
import com.safedata.fingerprint.entity.FingerPrintReport;
import com.safedata.fingerprint.repository.JobRepository;
import com.safedata.fingerprint.repository.ReportRepository;
import com.safedata.audit.entity.AuditRecord;
import com.safedata.audit.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
public class FingerPrintPdfGenerationService implements PdfGenerationService {

    private final TemplateEngine templateEngine;
    private final JobRepository jobRepository;
    private final ReportRepository reportRepository;
    private final AuditService auditService;
    private final HtmlToPdfConverter htmlToPdfConverter;
    private final PdfConfiguration pdfConfiguration;

    public FingerPrintPdfGenerationService(
            TemplateEngine templateEngine,
            JobRepository jobRepository,
            ReportRepository reportRepository,
            AuditService auditService,
            HtmlToPdfConverter htmlToPdfConverter,
            PdfConfiguration pdfConfiguration) {
        this.templateEngine = templateEngine;
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.auditService = auditService;
        this.htmlToPdfConverter = htmlToPdfConverter;
        this.pdfConfiguration = pdfConfiguration;
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] generateFingerPrintPdf(UUID jobId) throws PdfGenerationException {
        try {
            log.info("Generating fingerprint PDF for jobId: {}", jobId);
            long startTime = System.currentTimeMillis();

            // Fetch Job data
            FingerPrintJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new PdfGenerationException("Job not found: " + jobId));

            // Fetch Report data
            FingerPrintReport report = reportRepository.findByJobId(jobId)
                .orElseThrow(() -> new PdfGenerationException("Report not found for job: " + jobId));

            byte[] pdfBytes = generateFingerPrintPdf(job, report);
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("PDF generation completed for jobId: {} in {}ms", jobId, duration);
            
            return pdfBytes;
            
        } catch (PdfGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error generating fingerprint PDF for jobId: {}", jobId, e);
            throw new PdfGenerationException("Unable to create document", e);
        }
    }

    @Override
    public byte[] generateFingerPrintPdf(FingerPrintJob job, FingerPrintReport report) 
            throws PdfGenerationException {
        try {
            log.debug("Generating PDF from job and report objects");

            // Fetch audit records
            List<AuditRecord> auditRecords = auditService.findByDataAssetId(job.getDataAssetId());
            log.debug("Fetched {} audit records", auditRecords.size());

            // Prepare Thymeleaf context with all required data
            Context context = new Context();
            context.setVariable("job", job);
            context.setVariable("report", report);
            context.setVariable("auditRecords", auditRecords);
            context.setVariable("generatedAt", LocalDateTime.now());

            // Render template to HTML
            String templateName = pdfConfiguration.getTemplateName("fingerprint-job");
            log.debug("Rendering template: {}", templateName);
            String htmlContent = templateEngine.process(templateName, context);
            log.debug("HTML content generated, size: {} bytes", htmlContent.length());

            // Convert HTML to PDF
            byte[] pdfBytes = htmlToPdfConverter.convertHtmlToPdf(htmlContent);

            log.info("PDF successfully generated for jobId: {}, size: {} bytes", 
                     job.getId(), pdfBytes.length);
            return pdfBytes;

        } catch (PdfGenerationException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in PDF generation pipeline for job: {}", job.getId(), e);
            throw new PdfGenerationException("Unable to create document", e);
        }
    }

    @Override
    public String generatePdfFilename(UUID jobId) {
        try {
            String pattern = pdfConfiguration.getNamingPattern();
            String timestamp = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            
            return pattern
                .replace("{jobId}", jobId.toString())
                .replace("{timestamp}", timestamp);
                
        } catch (Exception e) {
            log.warn("Error generating filename with pattern, using default", e);
            return "fingerprint-report-" + jobId + ".pdf";
        }
    }
}
```

**Duration**: 3 hours

---

## Phase 3: REST Endpoint & Integration

### Task 3.1: Create REST Controller

**File**: `src/main/java/com/safedata/pdf/controller/PdfGenerationController.java`

```java
package com.safedata.pdf.controller;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.safedata.pdf.dto.PdfResponse;
import com.safedata.pdf.service.PdfGenerationService;
import com.safedata.pdf.exception.PdfGenerationException;
import lombok.extern.slf4j.Slf4j;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fingerprint-jobs")
@Slf4j
public class PdfGenerationController {

    private final PdfGenerationService pdfGenerationService;

    public PdfGenerationController(PdfGenerationService pdfGenerationService) {
        this.pdfGenerationService = pdfGenerationService;
    }

    /**
     * Generate and download fingerprint job report PDF
     * 
     * @param jobId The fingerprint job ID
     * @return ResponseEntity containing PDF bytes and filename
     */
    @GetMapping("/{jobId}/pdf")
    @PreAuthorize("hasRole('USER')")  // Spring Security protection
    public ResponseEntity<?> generateJobPdf(@PathVariable UUID jobId) {
        try {
            log.info("PDF generation request for jobId: {}", jobId);
            
            byte[] pdfBytes = pdfGenerationService.generateFingerPrintPdf(jobId);
            String filename = pdfGenerationService.generatePdfFilename(jobId);
            
            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentLength(pdfBytes.length);
            headers.setContentDispositionFormData("attachment", filename);

            log.info("PDF generated successfully for jobId: {}, size: {} bytes", 
                     jobId, pdfBytes.length);

            return ResponseEntity.ok()
                .headers(headers)
                .body(pdfBytes);

        } catch (PdfGenerationException e) {
            log.error("PDF generation failed for jobId: {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PdfResponse.error("Unable to create document"));
        } catch (Exception e) {
            log.error("Unexpected error during PDF generation for jobId: {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_JSON)
                .body(PdfResponse.error("Unable to create document"));
        }
    }
}
```

**Duration**: 2 hours (includes error handling and validation)

---

### Task 3.2: Create Thymeleaf Template

**File**: `src/main/resources/templates/pdf/fingerprint-report.html`

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="'Fingerprint Report - ' + ${job.id}">Fingerprint Report</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        body {
            font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
            color: #333;
            line-height: 1.6;
            background-color: #ffffff;
        }
        
        .container {
            max-width: 900px;
            margin: 0 auto;
            padding: 20px;
        }
        
        .header {
            border-bottom: 3px solid #0066cc;
            padding-bottom: 20px;
            margin-bottom: 30px;
        }
        
        .header h1 {
            color: #0066cc;
            font-size: 24px;
            margin-bottom: 10px;
        }
        
        .header-info {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 15px;
            font-size: 12px;
        }
        
        .info-item {
            padding: 8px;
            background-color: #f5f5f5;
            border-left: 3px solid #0066cc;
        }
        
        .info-item label {
            font-weight: bold;
            color: #0066cc;
        }
        
        .info-item value {
            display: block;
            margin-top: 4px;
        }
        
        .section {
            margin-bottom: 30px;
            page-break-inside: avoid;
        }
        
        .section-title {
            font-size: 16px;
            font-weight: bold;
            color: #0066cc;
            border-bottom: 2px solid #0066cc;
            padding-bottom: 10px;
            margin-bottom: 15px;
            margin-top: 20px;
        }
        
        table {
            width: 100%;
            border-collapse: collapse;
            margin-bottom: 15px;
            font-size: 11px;
        }
        
        table thead {
            background-color: #0066cc;
            color: white;
        }
        
        table th {
            padding: 12px;
            text-align: left;
            font-weight: bold;
            border: 1px solid #ccc;
        }
        
        table td {
            padding: 10px;
            border: 1px solid #ddd;
        }
        
        table tbody tr:nth-child(even) {
            background-color: #f9f9f9;
        }
        
        table tbody tr:hover {
            background-color: #f0f0f0;
        }
        
        .risk-score-highlight {
            background-color: #fff3cd;
            font-weight: bold;
            color: #856404;
        }
        
        .footer {
            margin-top: 40px;
            padding-top: 20px;
            border-top: 1px solid #ccc;
            font-size: 10px;
            color: #666;
            text-align: center;
        }
        
        .generated-at {
            font-size: 10px;
            color: #999;
            margin-top: 20px;
        }
    </style>
</head>
<body>
    <div class="container">
        <!-- Header Section -->
        <div class="header">
            <h1>Fingerprint Job Report</h1>
            <div class="header-info">
                <div class="info-item">
                    <label>Job ID</label>
                    <value th:text="${job.id}"></value>
                </div>
                <div class="info-item">
                    <label>Data Asset ID</label>
                    <value th:text="${job.dataAssetId}"></value>
                </div>
                <div class="info-item">
                    <label>Job Status</label>
                    <value th:text="${job.status}"></value>
                </div>
                <div class="info-item">
                    <label>Job Type</label>
                    <value th:text="${job.jobType}"></value>
                </div>
            </div>
        </div>

        <!-- Job Configuration Section -->
        <div class="section">
            <h2 class="section-title">Job Configuration</h2>
            <table>
                <tbody>
                    <tr>
                        <td style="width: 30%; font-weight: bold;">Status</td>
                        <td th:text="${job.status}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Job Type</td>
                        <td th:text="${job.jobType}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Created On</td>
                        <td th:text="${#dates.format(job.createdOn, 'yyyy-MM-dd HH:mm:ss')}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Started On</td>
                        <td th:text="${job.startedOn != null ? #dates.format(job.startedOn, 'yyyy-MM-dd HH:mm:ss') : 'Not Started'}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Completed On</td>
                        <td th:text="${job.completedOn != null ? #dates.format(job.completedOn, 'yyyy-MM-dd HH:mm:ss') : 'In Progress'}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Number of Rows</td>
                        <td th:text="${job.numberOfRows}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Number of Columns</td>
                        <td th:text="${job.numberOfColumns}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Selected Columns</td>
                        <td th:text="${#strings.listJoin(job.selectedColumns, ', ')}"></td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- Data Assets Audit Records Section -->
        <div class="section">
            <h2 class="section-title">Data Assets Audit Records</h2>
            <table th:if="${!auditRecords.isEmpty()}">
                <thead>
                    <tr>
                        <th>Created</th>
                        <th>Data Asset Name</th>
                        <th>Source</th>
                        <th>Actions</th>
                        <th>Status</th>
                        <th>BIA Indicator</th>
                        <th>Configuration</th>
                    </tr>
                </thead>
                <tbody>
                    <tr th:each="audit : ${auditRecords}">
                        <td th:text="${#dates.format(audit.created, 'yyyy-MM-dd HH:mm:ss')}"></td>
                        <td th:text="${audit.dataAssetName}"></td>
                        <td th:text="${audit.source}"></td>
                        <td th:text="${audit.actions}"></td>
                        <td th:text="${audit.status}"></td>
                        <td th:text="${audit.biaIndicator}"></td>
                        <td th:text="${audit.configuration}"></td>
                    </tr>
                </tbody>
            </table>
            <div th:if="${auditRecords.isEmpty()}" style="padding: 10px; background-color: #f0f0f0;">
                <p>No audit records found for this data asset.</p>
            </div>
        </div>

        <!-- Risk Assessment Summary Section -->
        <div class="section">
            <h2 class="section-title">Risk Assessment Summary</h2>
            <table>
                <tbody>
                    <tr>
                        <td style="width: 40%; font-weight: bold;">Execution Start Time</td>
                        <td th:text="${#dates.format(report.executionStartTime, 'yyyy-MM-dd HH:mm:ss')}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Execution End Time</td>
                        <td th:text="${#dates.format(report.executionEndTime, 'yyyy-MM-dd HH:mm:ss')}"></td>
                    </tr>
                    <tr>
                        <td style="font-weight: bold;">Algorithm Sample Size</td>
                        <td th:text="${report.algoSampleSize}"></td>
                    </tr>
                    <tr class="risk-score-highlight">
                        <td style="font-weight: bold;">Risk Score (Max Risk)</td>
                        <td th:text="${report.lowlevelAggregate.maxRisk}"></td>
                    </tr>
                </tbody>
            </table>
        </div>

        <!-- Footer -->
        <div class="footer">
            <p>This report was automatically generated by Safe Data Fingerprint Service</p>
            <div class="generated-at">
                <span th:text="'Generated: ' + ${#dates.format(generatedAt, 'yyyy-MM-dd HH:mm:ss')}"></span>
            </div>
        </div>
    </div>
</body>
</html>
```

**Duration**: 3 hours (includes design refinement)

---

### Task 3.3: Integration Testing

Create integration test for the REST endpoint:

**File**: `src/test/java/com/safedata/pdf/controller/PdfGenerationControllerTest.java`

```java
package com.safedata.pdf.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.UUID;

@SpringBootTest
@AutoConfigureMockMvc
class PdfGenerationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGeneratePdfEndpointSuccess() throws Exception {
        // Given a valid job ID
        UUID jobId = UUID.randomUUID();
        
        // When requesting PDF
        mockMvc.perform(get("/api/v1/fingerprint-jobs/{jobId}/pdf", jobId))
            // Then expect success
            .andExpect(status().isOk())
            .andExpect(content().contentType("application/pdf"));
    }

    @Test
    void testGeneratePdfEndpointNotFound() throws Exception {
        // Given non-existent job ID
        UUID jobId = UUID.randomUUID();
        
        // When requesting PDF
        mockMvc.perform(get("/api/v1/fingerprint-jobs/{jobId}/pdf", jobId))
            // Then expect error
            .andExpect(status().isInternalServerError());
    }
}
```

**Duration**: 2 hours

---

## Phase 4: RabbitMQ Integration

### Task 4.1: Create Job Completion Event

**File**: `src/main/java/com/safedata/fingerprint/event/JobCompletionEvent.java`

```java
package com.safedata.fingerprint.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.io.Serializable;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobCompletionEvent implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private UUID jobId;
    private UUID dataAssetId;
    private String userEmail;
    private String jobType;
    private Long completionTime;  // Unix timestamp
}
```

**Duration**: 1 hour

---

### Task 4.2: Create RabbitMQ Listener

**File**: `src/main/java/com/safedata/fingerprint/listener/JobCompletionListener.java`

```java
package com.safedata.fingerprint.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import com.safedata.pdf.service.PdfGenerationService;
import com.safedata.email.service.EmailService;
import com.safedata.fingerprint.event.JobCompletionEvent;
import lombok.extern.slf4j.Slf4j;
import java.util.UUID;

@Service
@Slf4j
public class JobCompletionListener {

    private final PdfGenerationService pdfGenerationService;
    private final EmailService emailService;

    public JobCompletionListener(
            PdfGenerationService pdfGenerationService,
            EmailService emailService) {
        this.pdfGenerationService = pdfGenerationService;
        this.emailService = emailService;
    }

    /**
     * Listen for job completion events and send notification email with PDF attachment
     */
    @RabbitListener(queues = "fingerprint.job.completion")
    public void handleJobCompletion(JobCompletionEvent event) {
        try {
            log.info("Processing job completion event for jobId: {}", event.getJobId());
            long startTime = System.currentTimeMillis();
            
            UUID jobId = event.getJobId();
            
            // Generate PDF by calling the generic service
            byte[] pdfBytes = pdfGenerationService.generateFingerPrintPdf(jobId);
            String filename = pdfGenerationService.generatePdfFilename(jobId);
            
            log.info("PDF generated for job completion notification, size: {} bytes", pdfBytes.length);
            
            // Send email with PDF attachment
            emailService.sendJobCompletionNotification(
                event.getUserEmail(),
                jobId,
                pdfBytes,
                filename
            );
            
            long duration = System.currentTimeMillis() - startTime;
            log.info("Job completion notification sent successfully for jobId: {} (duration: {}ms)", 
                     jobId, duration);
            
        } catch (Exception e) {
            log.error("Error handling job completion event for jobId: {}", event.getJobId(), e);
            // Could implement retry logic or DLQ handling here
            // For now, log and continue to prevent message processing from blocking
        }
    }
}
```

**Duration**: 2 hours

---

### Task 4.3: Configure RabbitMQ Queue

**File**: `src/main/java/com/safedata/fingerprint/config/RabbitMqConfig.java`

```java
package com.safedata.fingerprint.config;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {

    public static final String JOB_COMPLETION_QUEUE = "fingerprint.job.completion";

    @Bean
    public Queue jobCompletionQueue() {
        return new Queue(JOB_COMPLETION_QUEUE, true);  // Durable queue
    }
}
```

**Duration**: 1 hour

---

## Phase 5: Testing & Optimization

### Task 5.1: Unit Tests

Create unit tests for service layer:

**File**: `src/test/java/com/safedata/pdf/service/FingerPrintPdfGenerationServiceTest.java`

```java
package com.safedata.pdf.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.thymeleaf.TemplateEngine;
import com.safedata.pdf.converter.HtmlToPdfConverter;
import com.safedata.pdf.config.PdfConfiguration;
import com.safedata.fingerprint.entity.FingerPrintJob;
import com.safedata.fingerprint.entity.FingerPrintReport;
import com.safedata.fingerprint.repository.JobRepository;
import com.safedata.fingerprint.repository.ReportRepository;
import com.safedata.audit.service.AuditService;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.Optional;
import java.util.UUID;

@ExtendWith(MockitoExtension.class)
class FingerPrintPdfGenerationServiceTest {

    @Mock
    private TemplateEngine templateEngine;
    
    @Mock
    private JobRepository jobRepository;
    
    @Mock
    private ReportRepository reportRepository;
    
    @Mock
    private AuditService auditService;
    
    @Mock
    private HtmlToPdfConverter htmlToPdfConverter;
    
    @Mock
    private PdfConfiguration pdfConfiguration;

    @InjectMocks
    private FingerPrintPdfGenerationService service;

    @Test
    void testGeneratePdfWithValidJobId() {
        // Setup
        UUID jobId = UUID.randomUUID();
        FingerPrintJob job = new FingerPrintJob();
        job.setId(jobId);
        
        FingerPrintReport report = new FingerPrintReport();
        
        when(jobRepository.findById(jobId)).thenReturn(Optional.of(job));
        when(reportRepository.findByJobId(jobId)).thenReturn(Optional.of(report));
        when(auditService.findByDataAssetId(any())).thenReturn(java.util.List.of());
        when(templateEngine.process(anyString(), any())).thenReturn("<html></html>");
        when(htmlToPdfConverter.convertHtmlToPdf(anyString())).thenReturn(new byte[]{});
        when(pdfConfiguration.getTemplateName(anyString())).thenReturn("pdf/fingerprint-report");

        // Execute
        assertDoesNotThrow(() -> service.generateFingerPrintPdf(jobId));

        // Verify
        verify(jobRepository, times(1)).findById(jobId);
        verify(reportRepository, times(1)).findByJobId(jobId);
    }

    @Test
    void testGeneratePdfFilename() {
        // Setup
        UUID jobId = UUID.randomUUID();
        when(pdfConfiguration.getNamingPattern()).thenReturn("fingerprint-report-{jobId}-{timestamp}.pdf");

        // Execute
        String filename = service.generatePdfFilename(jobId);

        // Verify
        assertTrue(filename.contains(jobId.toString()));
        assertTrue(filename.endsWith(".pdf"));
    }
}
```

**Duration**: 3 hours

---

### Task 5.2: Performance Testing

Create performance test:

**File**: `src/test/java/com/safedata/pdf/service/PdfGenerationPerformanceTest.java`

```java
package com.safedata.pdf.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.safedata.pdf.config.PdfConfiguration;
import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;

@SpringBootTest
class PdfGenerationPerformanceTest {

    @Autowired
    private PdfGenerationService pdfGenerationService;

    @Autowired
    private PdfConfiguration pdfConfiguration;

    @Test
    void testPdfGenerationPerformanceSLA() {
        // Given a valid job ID
        UUID jobId = UUID.randomUUID();
        long maxExecutionTime = pdfConfiguration.getMaxExecutionTimeMs();  // 45 seconds

        // When generating PDF
        long startTime = System.currentTimeMillis();
        byte[] pdfBytes = pdfGenerationService.generateFingerPrintPdf(jobId);
        long duration = System.currentTimeMillis() - startTime;

        // Then it should complete within SLA
        assertTrue(duration < maxExecutionTime,
            String.format("PDF generation took %dms, exceeds SLA of %dms", duration, maxExecutionTime));
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 0);
    }
}
```

**Duration**: 2 hours

---

### Task 5.3: Load Testing with JMeter

Create JMeter test plan for the endpoint:

**Instructions:**
1. Set up JMeter with Thread Group (10 threads, ramp-up 10 seconds)
2. HTTP Request sampler targeting `/api/v1/fingerprint-jobs/{jobId}/pdf`
3. Response assertion expecting PDF content type
4. Analyze response times, throughput, error rates

**Duration**: 2 hours

---

### Task 5.4: Documentation & Code Review

- Create API documentation (Swagger/OpenAPI)
- Code review checklist
- Performance baseline documentation

**Duration**: 2 hours

---

## Deployment & Rollout

### Pre-Deployment Checklist

- [ ] All unit tests passing
- [ ] Integration tests passing
- [ ] Performance tests meeting SLA
- [ ] Security scanning (OWASP, dependency check)
- [ ] Code review completed
- [ ] Documentation completed
- [ ] Staging environment testing completed

### Deployment Steps

1. **Build and Test**
   ```bash
   mvn clean package
   mvn verify
   ```

2. **Deploy to Staging**
   - Deploy Docker image to staging environment
   - Run smoke tests
   - Monitor logs for errors

3. **Deploy to Production**
   - Use canary deployment (10% traffic)
   - Monitor metrics for 1 hour
   - Ramp up to 100% traffic
   - Monitor for 24 hours

### Rollback Plan

- Keep previous version running for 1 week
- Instant rollback if error rate > 1% or SLA breach

---

## Support & Monitoring

### Key Metrics to Monitor

1. **Performance**
   - PDF generation latency (p50, p95, p99)
   - Email delivery success rate
   - RabbitMQ queue depth

2. **Errors**
   - Exception rate
   - Job not found errors
   - Template rendering errors

3. **Business**
   - PDFs generated per day
   - Email delivery rate
   - User feedback

### Alerting Rules

- PDF generation latency > 30 seconds → Warning
- PDF generation latency > 40 seconds → Critical
- Error rate > 1% → Critical
- RabbitMQ queue depth > 1000 → Warning

### Logging

- Log all PDF generation requests with duration
- Log all errors with stack traces
- Log all email delivery attempts

---

## Timeline Summary

| Phase | Task | Duration | Total |
|-------|------|----------|-------|
| 1 | Setup & Infrastructure | 4.5 hours | 4.5 hours |
| 2 | Core Service | 8 hours | 12.5 hours |
| 3 | REST Endpoint | 7 hours | 19.5 hours |
| 4 | RabbitMQ Integration | 5 hours | 24.5 hours |
| 5 | Testing & Optimization | 9 hours | 33.5 hours |
| **Total Development Time** | | | **33.5 hours** |
| **Deployment & Rollout** | | 2-4 days | |

---

## Success Criteria

1. ✅ PDF generates in <45 seconds for all jobs
2. ✅ Email attachment includes properly formatted PDF
3. ✅ REST endpoint returns PDF with correct headers
4. ✅ Unit test coverage > 80%
5. ✅ Error handling for all failure scenarios
6. ✅ Zero data loss during generation
7. ✅ Configuration externalized in application.yaml
8. ✅ Template reusable for future PDF types

---

## Questions for Clarification

1. What existing EmailService interface should we use for sending emails?
2. Are there any specific audit record filters needed?
3. Should we implement retry logic for failed PDF generations?
4. Do you want metrics/monitoring integration (Prometheus)?
5. Should we cache generated PDFs temporarily for the same job?

