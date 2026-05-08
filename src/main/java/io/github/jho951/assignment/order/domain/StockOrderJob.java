package io.github.jho951.assignment.order.domain;

import io.github.jho951.assignment.brokerage.BrokerageOrderRequest;
import io.github.jho951.assignment.brokerage.BrokerageRemoteStatus;
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
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
    name = "stock_order_jobs",
    indexes = {
        @Index(name = "idx_stock_order_jobs_status_next_attempt", columnList = "status,next_attempt_at"),
        @Index(name = "idx_stock_order_jobs_status_lease_until", columnList = "status,lease_until"),
        @Index(name = "idx_stock_order_jobs_status_expires_at", columnList = "status,expires_at")
    },
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_stock_order_jobs_job_id", columnNames = "job_id"),
        @UniqueConstraint(name = "uk_stock_order_jobs_idempotency_key", columnNames = "idempotency_key")
    }
)
public class StockOrderJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private Long version;

    @Column(name = "job_id", nullable = false, length = 64)
    private String jobId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 128)
    private String requestHash;

    @Column(name = "brokerage_code", nullable = false, length = 32)
    private String brokerageCode;

    @Column(name = "account_number", nullable = false, length = 64)
    private String accountNumber;

    @Column(name = "symbol", nullable = false, length = 32)
    private String symbol;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_side", nullable = false, length = 16)
    private BrokerageOrderSide side;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 16)
    private BrokerageOrderType orderType;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "price", precision = 19, scale = 4)
    private BigDecimal price;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status;

    @Column(name = "external_order_id", length = 128)
    private String externalOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_status", length = 32)
    private BrokerageRemoteStatus executionStatus;

    @Column(name = "filled_quantity", nullable = false)
    private int filledQuantity;

    @Column(name = "remaining_quantity", nullable = false)
    private int remainingQuantity;

    @Column(name = "average_executed_price", precision = 19, scale = 4)
    private BigDecimal averageExecutedPrice;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", length = 512)
    private String errorMessage;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "lease_until")
    private Instant leaseUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    protected StockOrderJob() {
    }

    public static StockOrderJob queued(
        String jobId,
        String idempotencyKey,
        String requestHash,
        String brokerageCode,
        String accountNumber,
        String symbol,
        BrokerageOrderSide side,
        BrokerageOrderType orderType,
        int quantity,
        BigDecimal price,
        Instant nextAttemptAt
    ) {
        StockOrderJob job = new StockOrderJob();
        job.jobId = jobId;
        job.idempotencyKey = idempotencyKey;
        job.requestHash = requestHash;
        job.brokerageCode = brokerageCode;
        job.accountNumber = accountNumber;
        job.symbol = symbol;
        job.side = side;
        job.orderType = orderType;
        job.quantity = quantity;
        job.price = price;
        job.status = JobStatus.QUEUED;
        job.nextAttemptAt = nextAttemptAt;
        job.remainingQuantity = quantity;
        return job;
    }

    public BrokerageOrderRequest toBrokerageOrderRequest() {
        return new BrokerageOrderRequest(brokerageCode, accountNumber, symbol, side, orderType, quantity, price);
    }

    public boolean isTerminal() {
        return status.isTerminal();
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

    public String getBrokerageCode() {
        return brokerageCode;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getSymbol() {
        return symbol;
    }

    public BrokerageOrderSide getSide() {
        return side;
    }

    public BrokerageOrderType getOrderType() {
        return orderType;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getPrice() {
        return price;
    }

    public JobStatus getStatus() {
        return status;
    }

    public void setStatus(JobStatus status) {
        this.status = status;
    }

    public String getExternalOrderId() {
        return externalOrderId;
    }

    public void setExternalOrderId(String externalOrderId) {
        this.externalOrderId = externalOrderId;
    }

    public BrokerageRemoteStatus getExecutionStatus() {
        return executionStatus;
    }

    public void setExecutionStatus(BrokerageRemoteStatus executionStatus) {
        this.executionStatus = executionStatus;
    }

    public int getFilledQuantity() {
        return filledQuantity;
    }

    public void setFilledQuantity(int filledQuantity) {
        this.filledQuantity = filledQuantity;
    }

    public int getRemainingQuantity() {
        return remainingQuantity;
    }

    public void setRemainingQuantity(int remainingQuantity) {
        this.remainingQuantity = remainingQuantity;
    }

    public BigDecimal getAverageExecutedPrice() {
        return averageExecutedPrice;
    }

    public void setAverageExecutedPrice(BigDecimal averageExecutedPrice) {
        this.averageExecutedPrice = averageExecutedPrice;
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
