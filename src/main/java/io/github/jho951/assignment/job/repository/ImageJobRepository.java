package io.github.jho951.assignment.job.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import io.github.jho951.assignment.job.domain.ImageJob;
import io.github.jho951.assignment.job.domain.JobStatus;

public interface ImageJobRepository extends JpaRepository<ImageJob, Long> {

    Optional<ImageJob> findByJobId(String jobId);

    Optional<ImageJob> findByIdempotencyKey(String idempotencyKey);

    Page<ImageJob> findAllByStatus(JobStatus status, Pageable pageable);

    @Query("""
            select j
            from ImageJob j
            where j.status in :statuses
              and j.nextAttemptAt <= :now
            order by j.nextAttemptAt asc, j.createdAt asc, j.jobId asc
            """)
    List<ImageJob> findDueJobs(Collection<JobStatus> statuses, Instant now, Pageable pageable);

    @Query("""
            select j
            from ImageJob j
            where j.status = :status
              and j.leaseUntil is not null
              and j.leaseUntil < :now
            order by j.leaseUntil asc, j.createdAt asc, j.jobId asc
            """)
    List<ImageJob> findStaleProcessingJobs(JobStatus status, Instant now, Pageable pageable);

    @Query("""
            select j
            from ImageJob j
            where j.status in :statuses
              and j.expiresAt is not null
              and j.expiresAt < :now
            order by j.expiresAt asc
            """)
    List<ImageJob> findExpiredJobs(Collection<JobStatus> statuses, Instant now, Pageable pageable);
}
