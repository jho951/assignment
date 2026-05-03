package io.github.jho951.assignment.job.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

@Entity
@Table(
        name = "image_jobs",
        indexes = {
                @Index(name = "idx_image_jobs_status_next_attempt", columnList = "status,nextAttemptAt"),
                @Index(name = "idx_image_jobs_status_lease_until", columnList = "status,leaseUntil"),
                @Index(name = "idx_image_jobs_status_expires_at", columnList = "status,expiresAt")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_image_jobs_job_id", columnNames = "jobId"),
                @UniqueConstraint(name = "uk_image_jobs_idempotency_key", columnNames = "idempotencyKey")
        }
)
public class ImageJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(nullable = false, length = 64)
    private String jobId;

    @Column(nullable = false, length = 128)
    private String idempotencyKey;

    @Column(nullable = false, length = 128)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ImageInputType imageInputType;

    @Column(nullable = false, length = 2048)
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private JobStatus status;

    @Column(length = 128)
    private String externalJobId;

    @Column(length = 2048)
    private String result;

    @Column(length = 64)
    private String errorCode;

    @Column(length = 512)
    private String errorMessage;

    @Column(nullable = false)
    private int attemptCount;

    @Column(nullable = false)
    private Instant nextAttemptAt;

    private Instant leaseUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private Instant completedAt;

    private Instant expiresAt;

    protected ImageJob() {
    }

    public static ImageJob queued(
            String jobId,
            String idempotencyKey,
            String requestHash,
            String imageUrl,
            Instant nextAttemptAt
    ) {
        ImageJob imageJob = new ImageJob();
        imageJob.jobId = jobId;
        imageJob.idempotencyKey = idempotencyKey;
        imageJob.requestHash = requestHash;
        imageJob.imageInputType = ImageInputType.URL;
        imageJob.imageUrl = imageUrl;
        imageJob.status = JobStatus.QUEUED;
        imageJob.nextAttemptAt = nextAttemptAt;
        imageJob.attemptCount = 0;
        return imageJob;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    public Long getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public String getJobId() {
        return jobId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public ImageInputType getImageInputType() {
        return imageInputType;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getExternalJobId() {
        return externalJobId;
    }

    public void setExternalJobId(String externalJobId) {
        this.externalJobId = externalJobId;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(int attemptCount) {
        this.attemptCount = attemptCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public void setNextAttemptAt(Instant nextAttemptAt) {
        this.nextAttemptAt = nextAttemptAt;
    }

    public Instant getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(Instant leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }
}
