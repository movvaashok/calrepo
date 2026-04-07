# Safe Data PDF Generation - Implementation Guide for LLM

## Context

This document provides a structured implementation guide for building a PDF generation service in Java/Spring Boot that generates Fingerprint Job reports. The service should:
- Generate PDF from Fingerprint Job ID
- Support Thymeleaf templating for flexibility
- Be callable from both REST endpoint and RabbitMQ listener
- Meet 45-second SLA

## Technology Stack

- Java 25
- Spring Boot 3.10
- Thymeleaf 3.1.x
- Flying Saucer (OpenHTML2PDF) + iText 7
- RabbitMQ

## Project Structure

```
src/main/java/com/safedata/
├── pdf/
│   ├── controller/PdfGenerationController.java
│   ├── service/
│   │   ├── PdfGenerationService.java (interface)
│   │   └── FingerPrintPdfGenerationService.java (implementation)
│   ├── converter/
│   │   ├── HtmlToPdfConverter.java (interface)
│   │   └── FlyingSaucerHtmlToPdfConverter.java (implementation)
│   ├── config/PdfConfiguration.java
│   ├── exception/PdfGenerationException.java
│   └── dto/PdfResponse.java
├── fingerprint/
│   ├── listener/JobCompletionListener.java
│   ├── event/JobCompletionEvent.java
│   └── config/RabbitMqConfig.java
└── email/
    └── service/EmailService.java (existing service)

src/main/resources/
├── application.yaml
└── templates/
    └── pdf/
        └── fingerprint-report.html
```

## Detailed Implementation Steps

### Step 1: Maven Dependencies

Add to `pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-thymeleaf</artifactId>
</dependency>

<dependency>
    <groupId>org.xhtmlrenderer</groupId>
    <artifactId>core-renderer</artifactId>
    <version>9.7.2</version>
</dependency>

<dependency>
    <groupId>com.itextpdf</groupId>
    <artifactId>itext-core</artifactId>
    <version>8.0.4</version>
</dependency>

<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>
```

### Step 2: Create Configuration

File: `src/main/resources/application.yaml`

```yaml
spring:
  thymeleaf:
    prefix: classpath:/templates/
    suffix: .html
    mode: HTML
    cache: true

pdf:
  generation:
    enabled: true
    max-execution-time-ms: 45000
    naming-pattern: "fingerprint-report-{jobId}-{timestamp}.pdf"
    templates:
      fingerprint-job: "pdf/fingerprint-report"
    storage:
      strategy: "on-the-fly"
    converter:
      type: "flying-saucer"
      timeout-ms: 30000
```

### Step 3: Create Exception Class

File: `src/main/java/com/safedata/pdf/exception/PdfGenerationException.java`

- Extends RuntimeException
- Constructor: message only
- Constructor: message + cause
- Full constructor with enableSuppression and writableStackTrace

### Step 4: Create DTOs

File: `src/main/java/com/safedata/pdf/dto/PdfResponse.java`

Fields:
- pdfBytes: byte[]
- contentType: String
- filename: String
- errorMessage: String
- success: Boolean

Static methods:
- success(byte[], String, String): PdfResponse
- error(String): PdfResponse

### Step 5: Create Configuration Class

File: `src/main/java/com/safedata/pdf/config/PdfConfiguration.java`

- @Component + @ConfigurationProperties(prefix = "pdf.generation")
- Fields: enabled, maxExecutionTimeMs, namingPattern, templates (Map), storage, converter
- Method: getTemplateName(String key)
- Nested classes: StorageConfig, DataLakeConfig, ConverterConfig

### Step 6: Create HTML to PDF Converter Interface

File: `src/main/java/com/safedata/pdf/converter/HtmlToPdfConverter.java`

- Method: convertHtmlToPdf(String htmlContent) throws PdfGenerationException
- Returns: byte[]

### Step 7: Implement Flying Saucer Converter

File: `src/main/java/com/safedata/pdf/converter/FlyingSaucerHtmlToPdfConverter.java`

Implementation details:
1. Create ITextRenderer instance
2. Set document from HTML string
3. Call renderer.layout()
4. Create PDF to ByteArrayOutputStream
5. Return byte array
6. Handle DocumentException and other exceptions
7. Log duration and size

### Step 8: Create PDF Generation Service Interface

File: `src/main/java/com/safedata/pdf/service/PdfGenerationService.java`

Methods:
- generateFingerPrintPdf(UUID jobId): byte[]
- generateFingerPrintPdf(FingerPrintJob, FingerPrintReport): byte[]
- generatePdfFilename(UUID jobId): String

### Step 9: Implement PDF Generation Service

File: `src/main/java/com/safedata/pdf/service/FingerPrintPdfGenerationService.java`

Dependencies (via constructor):
- TemplateEngine
- JobRepository
- ReportRepository
- AuditService
- HtmlToPdfConverter
- PdfConfiguration

Implementation:
1. generateFingerPrintPdf(UUID jobId):
   - Fetch job from database
   - Fetch report from database
   - Call generateFingerPrintPdf(job, report)
   - Handle exceptions as PdfGenerationException

2. generateFingerPrintPdf(FingerPrintJob, FingerPrintReport):
   - Fetch audit records from AuditService
   - Create Thymeleaf Context
   - Add variables: job, report, auditRecords, generatedAt
   - Process template using TemplateEngine
   - Convert HTML to PDF using HtmlToPdfConverter
   - Return PDF bytes

3. generatePdfFilename(UUID jobId):
   - Get pattern from PdfConfiguration
   - Format current timestamp as yyyyMMdd_HHmmss
   - Replace {jobId} and {timestamp} placeholders
   - Return filename

### Step 10: Create Thymeleaf Template

File: `src/main/resources/templates/pdf/fingerprint-report.html`

Structure:
1. Header section with Job ID, Data Asset ID, Status, Type
2. Job Configuration section (table with job details)
3. Data Assets Audit Records section (table with 7 columns)
4. Risk Assessment Summary section (table with execution times and risk score)
5. Footer with generation timestamp

CSS:
- PDF-optimized styling
- Table borders and striping
- Highlight risk score with yellow background
- Clean typography

Template variables:
- job: FingerPrintJob
- report: FingerPrintReport
- auditRecords: List<AuditRecord>
- generatedAt: LocalDateTime

### Step 11: Create REST Controller

File: `src/main/java/com/safedata/pdf/controller/PdfGenerationController.java`

Endpoint: GET /api/v1/fingerprint-jobs/{jobId}/pdf

Implementation:
1. Accept pathVariable jobId (UUID)
2. Call PdfGenerationService.generateFingerPrintPdf(jobId)
3. On success:
   - Set HTTP headers: Content-Type, Content-Length, Content-Disposition
   - Return ResponseEntity with PDF bytes
4. On PdfGenerationException:
   - Return HTTP 500 with error JSON
5. On other exceptions:
   - Return HTTP 500 with error JSON
6. Add @PreAuthorize("hasRole('USER')") for security

### Step 12: Create Job Completion Event

File: `src/main/java/com/safedata/fingerprint/event/JobCompletionEvent.java`

Fields:
- jobId: UUID
- dataAssetId: UUID
- userEmail: String
- jobType: String
- completionTime: Long

Implements Serializable with serialVersionUID

### Step 13: Create RabbitMQ Listener

File: `src/main/java/com/safedata/fingerprint/listener/JobCompletionListener.java`

Annotation: @RabbitListener(queues = "fingerprint.job.completion")

Implementation:
1. Extract jobId from event
2. Call PdfGenerationService.generateFingerPrintPdf(jobId)
3. Get filename using generatePdfFilename(jobId)
4. Call EmailService.sendJobCompletionNotification(email, jobId, pdfBytes, filename)
5. Log success with duration
6. Catch all exceptions, log error, don't throw (prevent message requeue)

### Step 14: Create RabbitMQ Configuration

File: `src/main/java/com/safedata/fingerprint/config/RabbitMqConfig.java`

- @Configuration class
- Define Queue bean: "fingerprint.job.completion" (durable=true)

### Step 15: Create Tests

#### Unit Tests for Service

Test cases:
- Valid job ID generates PDF successfully
- Non-existent job throws exception
- Filename generation replaces placeholders correctly
- Audit records are included in context

#### Unit Tests for Converter

Test cases:
- HTML converts to PDF bytes
- Invalid HTML throws exception

#### Integration Tests for Controller

Test cases:
- GET endpoint returns 200 with PDF content-type
- Non-existent job returns 500 error
- Response headers are correct

#### Performance Tests

Test cases:
- PDF generation completes within 45-second SLA
- Multiple concurrent requests meet SLA

## Data Model Requirements

### Job Entity

Fields needed:
- id: UUID
- dataAssetId: UUID
- status: String/Enum
- jobType: String
- createdOn: LocalDateTime
- startedOn: LocalDateTime
- completedOn: LocalDateTime
- numberOfRows: Integer
- numberOfColumns: Integer
- selectedColumns: List<String>

Repository method needed:
- findById(UUID): Optional<FingerPrintJob>

### Report Entity

Fields needed:
- runId: UUID
- executionStartTime: LocalDateTime
- executionEndTime: LocalDateTime
- algoSampleSize: Integer
- lowlevelAggregate: LowLevelAggregate

Nested LowLevelAggregate:
- maxRisk: Double

Repository method needed:
- findByJobId(UUID): Optional<FingerPrintReport>

### Audit Record Entity

Fields needed:
- created: LocalDateTime
- dataAssetName: String
- source: String
- actions: String
- status: String
- biaIndicator: String
- configuration: String

Service method needed:
- findByDataAssetId(UUID): List<AuditRecord>

## Key Integration Points

1. **Database**: Must provide repositories for Job and Report
2. **AuditService**: Must provide method to fetch audit records by dataAssetId
3. **EmailService**: Must provide method to send emails with attachments
4. **Spring Security**: Endpoint is protected; ensure security context is configured
5. **RabbitMQ**: Must have exchange/queue set up for job.completion events
6. **Thymeleaf**: TemplateEngine is auto-configured by Spring Boot

## Performance Considerations

Target: <45 seconds SLA

Expected breakdown:
- Database queries: 100-300ms (3 queries with indexes)
- Thymeleaf rendering: 50-150ms (template caching enabled)
- PDF conversion: 1-3 seconds (typical report size)
- Total: 1.5-3.5 seconds (well under SLA)

Optimizations implemented:
- Thymeleaf template caching enabled
- Direct ByteArrayOutputStream (no file I/O)
- Transactional readOnly for query performance
- On-the-fly generation (no storage overhead)

## Configuration Externalization

All configurable items in application.yaml:
- PDF generation enabled/disabled
- Max execution time
- Naming pattern for generated files
- Template path
- Storage strategy (on-the-fly vs datalake)
- Converter timeout

This allows runtime configuration changes without code redployment.

## Error Handling Strategy

1. **Not Found Errors**: Throw PdfGenerationException with descriptive message
2. **Template Errors**: Catch and wrap in PdfGenerationException
3. **Conversion Errors**: Catch DocumentException and other PDF errors
4. **REST Controller**: Return HTTP 500 with JSON error response
5. **RabbitMQ Listener**: Log error, continue processing (no exception throwing)

## Security Considerations

1. REST endpoint protected with @PreAuthorize("hasRole('USER')")
2. Thymeleaf escapes template variables by default
3. No sensitive data in logs
4. PDF filename doesn't contain sensitive information

## Monitoring & Logging

Log at appropriate levels:
- INFO: PDF generation start/completion with duration
- DEBUG: Template rendering, data fetching
- ERROR: Exceptions with full stack traces
- WARN: Performance warnings (approaching SLA)

Metrics to track:
- PDF generation latency (p50, p95, p99)
- Error rate
- RabbitMQ queue depth

## Extension Points

Service designed for future extensions:
1. **Template switching**: Add more templates to templates map
2. **Converter switching**: Implement alternative HtmlToPdfConverter
3. **Storage**: Implement DataLakeStorageStrategy for saving PDFs
4. **Scheduling**: Add cron job to clean up old PDFs from datalake
5. **Caching**: Add caching layer for frequently generated reports

## Success Criteria Checklist

- [ ] PDF generates in <45 seconds
- [ ] Email receives PDF attachment correctly
- [ ] REST endpoint returns PDF with correct headers
- [ ] RabbitMQ listener integrates seamlessly
- [ ] All exceptions handled gracefully
- [ ] Configuration externalized
- [ ] Tests cover happy path and error cases
- [ ] Performance meets SLA under load
- [ ] Logging provides visibility
- [ ] No data loss during generation

## Deployment Checklist

- [ ] All dependencies compatible with Java 25
- [ ] Tests passing (unit + integration + performance)
- [ ] Security scanning completed
- [ ] Code review approved
- [ ] Staging environment tested
- [ ] RabbitMQ queue created
- [ ] Database indexes created on Job/Report repos
- [ ] Thymeleaf template validated
- [ ] Application.yaml configured correctly
- [ ] Monitoring/alerting configured

## Support & Troubleshooting

### Common Issues

1. **PDF not generating**: Check logs for Thymeleaf rendering errors
2. **Timeout**: Increase maxExecutionTimeMs in config
3. **Memory issues**: Check PDF size; may need pagination for large reports
4. **Template not found**: Verify template path in config and classpath
5. **Email not sent**: Check EmailService integration

### Debug Mode

Enable debug logging:
```yaml
logging:
  level:
    com.safedata.pdf: DEBUG
    org.thymeleaf: DEBUG
```

### Performance Profiling

Use JProfiler or YourKit to identify bottlenecks:
- Database query performance
- Template rendering time
- PDF conversion time

