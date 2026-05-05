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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
    @Setter
    private JobStatus status;

    @Column(length = 128)
    @Setter
    private String externalJobId;

    @Column(length = 2048)
    @Setter
    private String result;

    @Column(length = 64)
    @Setter
    private String errorCode;

    @Column(length = 512)
    @Setter
    private String errorMessage;

    @Column(nullable = false)
    @Setter
    private int attemptCount;

    @Column(nullable = false)
    @Setter
    private Instant nextAttemptAt;

    @Setter
    private Instant leaseUntil;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Setter
    private Instant completedAt;

    @Setter
    private Instant expiresAt;

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
}
