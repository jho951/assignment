package io.github.jho951.assignment.job.repository

import io.github.jho951.assignment.job.domain.ImageJob
import io.github.jho951.assignment.job.domain.JobStatus
import java.time.Instant
import java.util.Optional
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ImageJobRepository : JpaRepository<ImageJob, Long> {

    fun findByJobId(jobId: String): Optional<ImageJob>

    fun findByIdempotencyKey(idempotencyKey: String): Optional<ImageJob>

    @Query(
        value = """
        select j
        from ImageJob j
        where not (
            j.status in :terminalStatuses
            and j.expiresAt is not null
            and j.expiresAt <= :now
        )
        """,
        countQuery = """
        select count(j)
        from ImageJob j
        where not (
            j.status in :terminalStatuses
            and j.expiresAt is not null
            and j.expiresAt <= :now
        )
        """
    )
    fun findVisibleJobs(
        @Param("terminalStatuses") terminalStatuses: Collection<JobStatus>,
        @Param("now") now: Instant,
        pageable: Pageable
    ): Page<ImageJob>

    @Query(
        value = """
        select j
        from ImageJob j
        where j.status = :status
          and not (
              j.status in :terminalStatuses
              and j.expiresAt is not null
              and j.expiresAt <= :now
          )
        """,
        countQuery = """
        select count(j)
        from ImageJob j
        where j.status = :status
          and not (
              j.status in :terminalStatuses
              and j.expiresAt is not null
              and j.expiresAt <= :now
          )
        """
    )
    fun findVisibleJobsByStatus(
        @Param("status") status: JobStatus,
        @Param("terminalStatuses") terminalStatuses: Collection<JobStatus>,
        @Param("now") now: Instant,
        pageable: Pageable
    ): Page<ImageJob>

    @Query(
        """
        select j
        from ImageJob j
        where j.status in :statuses
          and j.nextAttemptAt <= :now
        order by j.nextAttemptAt asc, j.createdAt asc, j.jobId asc
        """
    )
    fun findDueJobs(
        @Param("statuses") statuses: Collection<JobStatus>,
        @Param("now") now: Instant,
        pageable: Pageable
    ): List<ImageJob>

    @Query(
        """
        select j
        from ImageJob j
        where j.status = :status
          and j.leaseUntil is not null
          and j.leaseUntil < :now
        order by j.leaseUntil asc, j.createdAt asc, j.jobId asc
        """
    )
    fun findStaleProcessingJobs(
        @Param("status") status: JobStatus,
        @Param("now") now: Instant,
        pageable: Pageable
    ): List<ImageJob>

    @Query(
        """
        select j
        from ImageJob j
        where j.status in :statuses
          and j.expiresAt is not null
          and j.expiresAt <= :now
        order by j.expiresAt asc
        """
    )
    fun findExpiredJobs(
        @Param("statuses") statuses: Collection<JobStatus>,
        @Param("now") now: Instant,
        pageable: Pageable
    ): List<ImageJob>
}
