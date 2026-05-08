package io.github.jho951.assignment.order.repository;

import io.github.jho951.assignment.order.domain.JobStatus;
import io.github.jho951.assignment.order.domain.StockOrderJob;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockOrderJobRepository extends JpaRepository<StockOrderJob, Long> {

    Optional<StockOrderJob> findByJobId(String jobId);

    Optional<StockOrderJob> findByIdempotencyKey(String idempotencyKey);

    @Query(
        value = """
            select j
            from StockOrderJob j
            where not (
                j.status in :terminalStatuses
                and j.expiresAt is not null
                and j.expiresAt <= :now
            )
            """,
        countQuery = """
            select count(j)
            from StockOrderJob j
            where not (
                j.status in :terminalStatuses
                and j.expiresAt is not null
                and j.expiresAt <= :now
            )
            """
    )
    Page<StockOrderJob> findVisibleJobs(
        @Param("terminalStatuses") Collection<JobStatus> terminalStatuses,
        @Param("now") Instant now,
        Pageable pageable
    );

    @Query(
        value = """
            select j
            from StockOrderJob j
            where j.status = :status
              and not (
                  j.status in :terminalStatuses
                  and j.expiresAt is not null
                  and j.expiresAt <= :now
              )
            """,
        countQuery = """
            select count(j)
            from StockOrderJob j
            where j.status = :status
              and not (
                  j.status in :terminalStatuses
                  and j.expiresAt is not null
                  and j.expiresAt <= :now
              )
            """
    )
    Page<StockOrderJob> findVisibleJobsByStatus(
        @Param("status") JobStatus status,
        @Param("terminalStatuses") Collection<JobStatus> terminalStatuses,
        @Param("now") Instant now,
        Pageable pageable
    );

    @Query(
        """
        select j
        from StockOrderJob j
        where j.status in :statuses
          and j.nextAttemptAt <= :now
        order by j.nextAttemptAt asc, j.createdAt asc, j.jobId asc
        """
    )
    List<StockOrderJob> findDueJobs(
        @Param("statuses") Collection<JobStatus> statuses,
        @Param("now") Instant now,
        Pageable pageable
    );

    @Query(
        """
        select j
        from StockOrderJob j
        where j.status = :status
          and j.leaseUntil is not null
          and j.leaseUntil < :now
        order by j.leaseUntil asc, j.createdAt asc, j.jobId asc
        """
    )
    List<StockOrderJob> findStaleProcessingJobs(
        @Param("status") JobStatus status,
        @Param("now") Instant now,
        Pageable pageable
    );

    @Query(
        """
        select j
        from StockOrderJob j
        where j.status in :statuses
          and j.expiresAt is not null
          and j.expiresAt <= :now
        order by j.expiresAt asc
        """
    )
    List<StockOrderJob> findExpiredJobs(
        @Param("statuses") Collection<JobStatus> statuses,
        @Param("now") Instant now,
        Pageable pageable
    );
}
