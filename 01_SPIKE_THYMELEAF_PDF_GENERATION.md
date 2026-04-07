# Spike: Thymeleaf-Based PDF Generation for Safe Data

## Executive Summary

This spike evaluates the use of **Thymeleaf as a template engine** combined with **HTML to PDF conversion libraries** for generating PDF reports in Safe Data's Fingerprint job completion notifications. The approach provides flexibility, maintainability, and reusability for static template-based PDF generation.

---

## 1. Technology Evaluation

### Selected Technology Stack
- **Template Engine**: Thymeleaf 3.1.x
- **PDF Library**: Flying Saucer (OpenHTML2PDF) + iText 7
- **Java Version**: Java 25, Spring Boot 3.10

### Why This Stack?

#### Thymeleaf
**Pros:**
- Native Spring Boot integration (spring-boot-starter-thymeleaf)
- HTML/CSS-based templates (easier to design and maintain)
- Reusable across web and backend services
- No additional template syntax learning curve
- Security features built-in (XSS protection)
- Zero vulnerabilities in latest versions
- Easy to version control and test

**Cons:**
- Slightly heavier than lightweight options
- Minimal impact for this use case

#### Flying Saucer (OpenHTML2PDF)
**Pros:**
- Pure Java, no system dependencies (no Ghostscript, no wkhtmltopdf)
- Excellent CSS/HTML support
- Best for programmatic PDF generation from HTML
- Lightweight and fast
- Open source with active maintenance
- Well-suited for <45 second SLA

**Cons:**
- Some advanced CSS features not fully supported
- Table rendering is excellent but complex layouts need testing

#### Why NOT Other Options?

| Library | Reason Not Selected |
|---------|------------------|
| iText Standalone | Requires learning iText APIs; HTML support is limited without Flying Saucer wrapper |
| Apache PDFBox | Better for reading/modifying; slower for HTML to PDF |
| JasperReports | Heavy enterprise tool; overkill for this use case; complex templating |
| Freemarker | Similar to Thymeleaf but less Spring integration; weaker HTML support |
| Velocity | Deprecated; not recommended for new projects |

---

## 2. Architecture Overview

### System Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    REST Endpoint Layer                   │
│  GET /api/v1/fingerprint-jobs/{jobId}/pdf              │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              PDF Generation Service Layer                │
│  (Generic: PDF Generation Service)                       │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼──────┐  ┌────▼──────┐  ┌───▼────────────┐
│   Thymeleaf  │  │  Template │  │ Data Enrichment│
│   Engine     │  │  Resolver │  │   Service      │
└───────┬──────┘  └────┬──────┘  └───┬────────────┘
        │              │              │
        └──────────────┼──────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│        HTML to PDF Conversion (Flying Saucer)           │
└──────────────────────┬──────────────────────────────────┘
                       │
                ┌──────▼──────┐
                │  PDF Bytes   │
                └──────┬───────┘
                       │
        ┌──────────────┼──────────────┐
        │              │              │
┌───────▼────────┐  ┌──▼──────────┐ ┌▼──────────────┐
│ REST Response  │  │ Email Queue  │ │ Datalake      │
│ (PdfResponse)  │  │ (RabbitMQ)   │ │ (if needed)   │
└────────────────┘  └─────────────┘ └───────────────┘
```

### Component Details

#### 1. REST Controller
- Receives GET request with jobId path parameter
- Calls PDF Service
- Returns ResponseEntity wrapping PDF bytes
- Handles exceptions and returns HTTP error status

#### 2. PDF Generation Service (Generic)
- Orchestrates PDF generation process
- Template resolution and rendering
- Data enrichment from database
- HTML to PDF conversion
- Error handling and logging
- Callable from both REST endpoint and RabbitMQ listener

#### 3. Template Management
- Thymeleaf template stored in `classpath:/templates/pdf/`
- Single configurable template for Fingerprint reports
- Supports context variables for dynamic data
- CSS styling embedded in template

#### 4. Data Service
- Fetches Job and Report data
- Enriches with audit records
- Transforms data for template consumption
- Handles data not found scenarios

#### 5. PDF Converter
- Converts HTML string to PDF bytes
- Handles sizing, margins, fonts
- Manages performance (target <45 seconds per PDF)

---

## 3. Database Model Mapping

### Job Entity
```java
public class FingerPrintJob {
    private UUID id;
    private UUID dataAssetId;
    private JobStatus status;
    private String jobType;
    private LocalDateTime createdOn;
    private LocalDateTime startedOn;
    private LocalDateTime completedOn;
    private Integer numberOfRows;
    private Integer numberOfColumns;
    private List<String> selectedColumns;  // Job Configuration
}
```

### Report Entity
```java
public class FingerPrintReport {
    private UUID runId;
    private LocalDateTime executionStartTime;
    private LocalDateTime executionEndTime;
    private Integer algoSampleSize;
    private LowLevelAggregate lowlevelAggregate;
    
    // nested
    private RowStats rowStats;
    private List<ColumnStats> columnStats;
    private List<ColumnRisk> columnRisks;
    private List<ColumnRisk> criticalColumnRisks;
    private JobResources jobResources;
}

public class LowLevelAggregate {
    private Double maxRisk;  // Risk Score
    // other fields
}
```

### Audit Record Model
```java
public class AuditRecord {
    private LocalDateTime created;
    private String dataAssetName;
    private String source;
    private String actions;
    private String status;
    private String biaIndicator;
    private String configuration;
}
```

---

## 4. Code Architecture & Design Patterns

### Design Pattern: Strategy Pattern
- Template resolution strategy (configurable template paths)
- PDF converter strategy (pluggable HTML to PDF converters)

### Key Interfaces

```java
// Template Resolver
public interface PdfTemplateResolver {
    String resolveTemplate(String templateName, Context context);
}

// PDF Converter
public interface HtmlToPdfConverter {
    byte[] convertHtmlToPdf(String htmlContent) throws PdfConversionException;
}

// PDF Service (Main)
public interface PdfGenerationService {
    byte[] generateFingerPrintPdf(UUID jobId);
    byte[] generateFingerPrintPdf(FingerPrintJob job, FingerPrintReport report);
}
```

### Service Layer Architecture

```
PdfGenerationServiceImpl (Generic)
├── Thymeleaf Template Engine
├── JobRepository (fetch job)
├── ReportRepository (fetch report)
├── AuditService (fetch audit records)
├── HtmlToPdfConverter (Flying Saucer)
└── PdfNameConfigurer (naming from application.yaml)
```

---

## 5. Template Structure (Thymeleaf HTML)

### Template Hierarchy

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org">
<head>
    <meta charset="UTF-8">
    <title th:text="'Fingerprint Report - ' + ${job.id}">Report</title>
    <style>
        /* PDF-optimized CSS */
        body { font-family: Arial, sans-serif; margin: 20px; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; }
        th { background-color: #f0f0f0; border: 1px solid #ddd; padding: 8px; }
        td { border: 1px solid #ddd; padding: 8px; }
        .header { color: #333; margin-bottom: 20px; }
        .section-title { font-size: 14px; font-weight: bold; margin-top: 20px; }
    </style>
</head>
<body>
    <!-- Header Section -->
    <div class="header">
        <h1>Fingerprint Job Report</h1>
        <p th:text="'Job ID: ' + ${job.id}"></p>
        <p th:text="'Data Asset: ' + ${job.dataAssetId}"></p>
    </div>

    <!-- Job Configuration Section -->
    <div class="section">
        <h2 class="section-title">Job Configuration</h2>
        <table>
            <tr>
                <td>Status</td>
                <td th:text="${job.status}"></td>
            </tr>
            <tr>
                <td>Job Type</td>
                <td th:text="${job.jobType}"></td>
            </tr>
            <tr>
                <td>Created On</td>
                <td th:text="${#dates.format(job.createdOn, 'yyyy-MM-dd HH:mm:ss')}"></td>
            </tr>
            <tr>
                <td>Started On</td>
                <td th:text="${job.startedOn != null ? #dates.format(job.startedOn, 'yyyy-MM-dd HH:mm:ss') : 'N/A'}"></td>
            </tr>
            <tr>
                <td>Completed On</td>
                <td th:text="${job.completedOn != null ? #dates.format(job.completedOn, 'yyyy-MM-dd HH:mm:ss') : 'N/A'}"></td>
            </tr>
            <tr>
                <td>Number of Rows</td>
                <td th:text="${job.numberOfRows}"></td>
            </tr>
            <tr>
                <td>Number of Columns</td>
                <td th:text="${job.numberOfColumns}"></td>
            </tr>
            <tr>
                <td>Selected Columns</td>
                <td th:text="${#strings.listJoin(job.selectedColumns, ', ')}"></td>
            </tr>
        </table>
    </div>

    <!-- Audit Records Section -->
    <div class="section">
        <h2 class="section-title">Data Assets Audit Records</h2>
        <table>
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
    </div>

    <!-- Risk Assessment Section -->
    <div class="section">
        <h2 class="section-title">Risk Assessment Summary</h2>
        <table>
            <tr>
                <td>Execution Start Time</td>
                <td th:text="${#dates.format(report.executionStartTime, 'yyyy-MM-dd HH:mm:ss')}"></td>
            </tr>
            <tr>
                <td>Execution End Time</td>
                <td th:text="${#dates.format(report.executionEndTime, 'yyyy-MM-dd HH:mm:ss')}"></td>
            </tr>
            <tr>
                <td>Algorithm Sample Size</td>
                <td th:text="${report.algoSampleSize}"></td>
            </tr>
            <tr>
                <td style="font-weight: bold; background-color: #fff3cd;">Risk Score (Max Risk)</td>
                <td style="font-weight: bold; background-color: #fff3cd;" th:text="${report.lowlevelAggregate.maxRisk}"></td>
            </tr>
        </table>
    </div>
</body>
</html>
```

---

## 6. Code Samples

### 6.1 Maven Dependencies

```xml
<!-- Thymeleaf -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>

<!-- Flying Saucer (OpenHTML2PDF) for HTML to PDF -->
<dependency>
    <groupId>org.xhtmlrenderer</groupId>
    <artifactId>core-renderer</artifactId>
    <version>9.7.2</version>
</dependency>

<!-- iText for advanced PDF features (pulled by Flying Saucer) -->
<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itext-core</artifactId>
    <version>8.0.4</version>
</dependency>

<!-- Logging -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-logging</artifactId>
</dependency>
```

### 6.2 Configuration Class (application.yaml)

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

pdf:
  generation:
    enabled: true
    max-execution-time: 45000  # 45 seconds in milliseconds
    naming-pattern: "fingerprint-report-{jobId}-{timestamp}.pdf"  # Configurable pattern
    templates:
      fingerprint-job: "pdf/fingerprint-report"  # Template path without extension
    storage:
      strategy: "on-the-fly"  # Options: on-the-fly, datalake
      datalake:
        retention-days: 0  # 0 means no retention (delete after email sent)
        path: "s3://safe-data-datalake/pdf-reports/"
```

### 6.3 Service Implementation

```java
package com.safedata.pdf.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import com.safedata.fingerprint.entity.FingerPrintJob;
import com.safedata.fingerprint.entity.FingerPrintReport;
import com.safedata.audit.entity.AuditRecord;
import com.safedata.audit.service.AuditService;
import com.safedata.fingerprint.repository.JobRepository;
import com.safedata.fingerprint.repository.ReportRepository;
import com.safedata.pdf.converter.HtmlToPdfConverter;
import com.safedata.pdf.config.PdfConfiguration;
import com.safedata.pdf.dto.PdfGenerationException;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;
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
            
            // Fetch Job data
            FingerPrintJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new PdfGenerationException("Job not found: " + jobId));

            // Fetch Report data
            FingerPrintReport report = reportRepository.findByJobId(jobId)
                .orElseThrow(() -> new PdfGenerationException("Report not found for job: " + jobId));

            return generateFingerPrintPdf(job, report);
        } catch (Exception e) {
            log.error("Error generating fingerprint PDF", e);
            throw new PdfGenerationException("Unable to create document", e);
        }
    }

    @Override
    public byte[] generateFingerPrintPdf(FingerPrintJob job, FingerPrintReport report) throws PdfGenerationException {
        try {
            // Fetch audit records
            List<AuditRecord> auditRecords = auditService.findByDataAssetId(job.getDataAssetId());

            // Prepare Thymeleaf context
            Context context = new Context();
            context.setVariable("job", job);
            context.setVariable("report", report);
            context.setVariable("auditRecords", auditRecords);

            // Render template to HTML
            String templateName = pdfConfiguration.getTemplateName("fingerprint-job");
            String htmlContent = templateEngine.process(templateName, context);

            log.debug("HTML content generated for job: {}", job.getId());

            // Convert HTML to PDF
            byte[] pdfBytes = htmlToPdfConverter.convertHtmlToPdf(htmlContent);

            log.info("PDF successfully generated for jobId: {}", job.getId());
            return pdfBytes;

        } catch (Exception e) {
            log.error("Error in PDF generation pipeline", e);
            throw new PdfGenerationException("Unable to create document", e);
        }
    }

    /**
     * Generates PDF filename based on configured naming pattern
     * Pattern supports: {jobId}, {timestamp}, {dataAssetId}
     */
    public String generatePdfFilename(UUID jobId) {
        String pattern = pdfConfiguration.getNamingPattern();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        
        return pattern
            .replace("{jobId}", jobId.toString())
            .replace("{timestamp}", timestamp)
            .replace("{dataAssetId}", "data-asset-id");  // Can be enhanced
    }
}
```

### 6.4 HTML to PDF Converter (Flying Saucer Implementation)

```java
package com.safedata.pdf.converter;

import com.lowagie.text.DocumentException;
import org.xhtmlrenderer.pdf.ITextRenderer;
import org.springframework.stereotype.Component;
import com.safedata.pdf.dto.PdfGenerationException;
import lombok.extern.slf4j.Slf4j;
import java.io.ByteArrayOutputStream;

@Component
@Slf4j
public class FlyingSaucerHtmlToPdfConverter implements HtmlToPdfConverter {

    @Override
    public byte[] convertHtmlToPdf(String htmlContent) throws PdfGenerationException {
        try {
            ITextRenderer renderer = new ITextRenderer();
            
            // Load HTML content
            String baseUrl = "file:///";  // Can be configured for external resources
            renderer.setDocumentFromString(htmlContent, baseUrl);
            
            // Layout the document
            renderer.layout();
            
            // Render to PDF
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            renderer.createPDF(outputStream);
            
            byte[] pdfBytes = outputStream.toByteArray();
            log.info("PDF conversion completed, size: {} bytes", pdfBytes.length);
            
            return pdfBytes;
        } catch (DocumentException e) {
            log.error("PDF rendering error", e);
            throw new PdfGenerationException("Unable to create document", e);
        } catch (Exception e) {
            log.error("Unexpected error during PDF conversion", e);
            throw new PdfGenerationException("Unable to create document", e);
        }
    }
}
```

### 6.5 REST Controller

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
import com.safedata.pdf.dto.PdfGenerationException;
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
     * @return ResponseEntity containing PDF bytes
     */
    @GetMapping("/{jobId}/pdf")
    @PreAuthorize("hasRole('USER')")  // Spring Security protection
    public ResponseEntity<PdfResponse> generateJobPdf(@PathVariable UUID jobId) {
        try {
            log.info("PDF generation request for jobId: {}", jobId);
            
            byte[] pdfBytes = pdfGenerationService.generateFingerPrintPdf(jobId);
            
            // Create response object
            PdfResponse response = PdfResponse.builder()
                .pdfBytes(pdfBytes)
                .contentType(MediaType.APPLICATION_PDF_VALUE)
                .filename(pdfGenerationService.generatePdfFilename(jobId))
                .build();

            // Set response headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_PDF);
            headers.setContentLength(pdfBytes.length);
            headers.setContentDispositionFormData("attachment", response.getFilename());

            return ResponseEntity.ok()
                .headers(headers)
                .body(response);

        } catch (PdfGenerationException e) {
            log.error("PDF generation failed for jobId: {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PdfResponse.error("Unable to create document"));
        } catch (Exception e) {
            log.error("Unexpected error during PDF generation", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(PdfResponse.error("Unable to create document"));
        }
    }
}
```

### 6.6 RabbitMQ Listener Integration

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

    @RabbitListener(queues = "fingerprint.job.completion")
    public void handleJobCompletion(JobCompletionEvent event) {
        try {
            log.info("Processing job completion event for jobId: {}", event.getJobId());
            
            UUID jobId = event.getJobId();
            
            // Generate PDF by calling the generic service
            byte[] pdfBytes = pdfGenerationService.generateFingerPrintPdf(jobId);
            String filename = pdfGenerationService.generatePdfFilename(jobId);
            
            // Send email with PDF attachment
            emailService.sendJobCompletionNotification(
                event.getUserEmail(),
                jobId,
                pdfBytes,
                filename
            );
            
            log.info("Job completion email sent successfully for jobId: {}", jobId);
            
        } catch (Exception e) {
            log.error("Error handling job completion event", e);
            // Could implement retry logic or DLQ handling here
        }
    }
}
```

### 6.7 Exception Handling

```java
package com.safedata.pdf.dto;

public class PdfGenerationException extends RuntimeException {
    
    public PdfGenerationException(String message) {
        super(message);
    }

    public PdfGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

### 6.8 Response DTO

```java
package com.safedata.pdf.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PdfResponse {
    private byte[] pdfBytes;
    private String contentType;
    private String filename;
    private String errorMessage;

    public static PdfResponse error(String message) {
        return PdfResponse.builder()
            .errorMessage(message)
            .build();
    }
}
```

---

## 7. Performance Considerations

### Meeting 45-Second SLA

**Identified Bottlenecks:**
1. Database queries (fetch job, report, audit records)
2. Thymeleaf template processing
3. HTML to PDF conversion
4. Memory allocation for PDF buffer

**Optimization Strategies:**
1. **Database**: Use indexed queries, consider caching frequently accessed reports
2. **Thymeleaf**: Cache compiled templates (enabled by default in production)
3. **Flying Saucer**: Render directly to ByteArrayOutputStream (no intermediate file I/O)
4. **Async Processing**: For RabbitMQ listener, process asynchronously

**Expected Performance:**
- Database fetch: 100-300ms
- Template rendering: 50-150ms
- HTML to PDF conversion: 1-3 seconds (typical for 2-5 page documents)
- **Total: 1.5-3.5 seconds** (well under 45-second SLA)

### Memory Management
- PDF bytes stored in memory (typical report ~200-500KB)
- No file I/O overhead in on-the-fly generation approach
- Suitable for cloud-native deployment

---

## 8. Alternative Template Formats (Future Flexibility)

The service is designed to support alternative template formats:

```java
public interface PdfTemplateResolver {
    String resolveTemplate(String templateName, Context context);
}

// Future implementations:
// - FreemarkerTemplateResolver
// - VelocityTemplateResolver
// - MarkdownTemplateResolver
// - XSLTemplateResolver
```

---

## 9. Recommendations

1. **Use Flying Saucer + Thymeleaf**: Best balance of simplicity, performance, and maintainability
2. **On-the-fly Generation**: Start with generating PDFs on demand; storage overhead is minimal
3. **Template Management**: Store templates in classpath; version control alongside code
4. **Configuration Externalization**: Use application.yaml for all naming patterns and template paths
5. **Error Handling**: Comprehensive logging and meaningful error messages for troubleshooting

---

## 10. Spike Completion Checklist

- [x] Evaluated PDF generation libraries
- [x] Recommended technology stack
- [x] Designed system architecture
- [x] Provided code samples
- [x] Template structure design
- [x] Performance analysis
- [x] RabbitMQ integration approach
- [x] Configuration strategy

---

## Appendix: Vulnerability Assessment

### Selected Libraries - Security Status
- **Thymeleaf 3.1.x**: No known vulnerabilities (checks OWASP database)
- **Flying Saucer 9.7.2**: No critical vulnerabilities; actively maintained
- **iText 8.0.4**: AGPL license; GPLv3 compatible for open-source; no unpatched CVEs

All dependencies can be scanned with `mvn dependency-check:check` for continuous security monitoring.
