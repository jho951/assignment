package io.github.jho951.assignment.job.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.PrePersist
import jakarta.persistence.PreUpdate
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant

@Entity
@Table(
    name = "image_jobs",
    indexes = [
        Index(name = "idx_image_jobs_status_next_attempt", columnList = "status,nextAttemptAt"),
        Index(name = "idx_image_jobs_status_lease_until", columnList = "status,leaseUntil"),
        Index(name = "idx_image_jobs_status_expires_at", columnList = "status,expiresAt")
    ],
    uniqueConstraints = [
        UniqueConstraint(name = "uk_image_jobs_job_id", columnNames = ["jobId"]),
        UniqueConstraint(name = "uk_image_jobs_idempotency_key", columnNames = ["idempotencyKey"])
    ]
)
open class ImageJob protected constructor() {

    @field:Id
    @field:GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null
        protected set

    @field:Version
    open var version: Long? = null
        protected set

    @field:Column(nullable = false, length = 64)
    open lateinit var jobId: String
        protected set

    @field:Column(nullable = false, length = 128)
    open lateinit var idempotencyKey: String
        protected set

    @field:Column(nullable = false, length = 128)
    open lateinit var requestHash: String
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 16)
    open lateinit var imageInputType: ImageInputType
        protected set

    @field:Column(nullable = false, length = 2048)
    open lateinit var imageUrl: String
        protected set

    @field:Enumerated(EnumType.STRING)
    @field:Column(nullable = false, length = 32)
    open lateinit var status: JobStatus

    @field:Column(length = 128)
    open var externalJobId: String? = null

    @field:Column(length = 2048)
    open var result: String? = null

    @field:Column(length = 64)
    open var errorCode: String? = null

    @field:Column(length = 512)
    open var errorMessage: String? = null

    @field:Column(nullable = false)
    open var attemptCount: Int = 0

    @field:Column(nullable = false)
    open var nextAttemptAt: Instant? = null

    open var leaseUntil: Instant? = null

    @field:Column(nullable = false, updatable = false)
    open var createdAt: Instant? = null
        protected set

    @field:Column(nullable = false)
    open var updatedAt: Instant? = null
        protected set

    open var completedAt: Instant? = null

    open var expiresAt: Instant? = null

    @PrePersist
    fun onCreate() {
        val now = Instant.now()
        if (createdAt == null) {
            createdAt = now
        }
        updatedAt = now
    }

    @PreUpdate
    fun onUpdate() {
        updatedAt = Instant.now()
    }

    fun isTerminal(): Boolean = status.isTerminal()

    companion object {
        @JvmStatic
        fun queued(
            jobId: String,
            idempotencyKey: String,
            requestHash: String,
            imageUrl: String,
            nextAttemptAt: Instant
        ): ImageJob {
            val imageJob = ImageJob()
            imageJob.jobId = jobId
            imageJob.idempotencyKey = idempotencyKey
            imageJob.requestHash = requestHash
            imageJob.imageInputType = ImageInputType.URL
            imageJob.imageUrl = imageUrl
            imageJob.status = JobStatus.QUEUED
            imageJob.nextAttemptAt = nextAttemptAt
            imageJob.attemptCount = 0
            return imageJob
        }
    }
}
