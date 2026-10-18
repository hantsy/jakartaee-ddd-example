package library.lending.domain;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

@Entity
public class Loan {
    private static final int LOAN_TERM_DAYS = 30;

    @EmbeddedId
    private LoanId loanId;
    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "copy_id"))
    private CopyId copyId;
    @Embedded
    @AttributeOverride(name = "id", column = @Column(name = "user_id"))
    private UserId userId;
    private LocalDateTime createdAt;
    private LocalDate expectedReturnDate;
    private LocalDateTime returnedAt;
    private BigDecimal overdueFee;

    @Version
    private Long version;

    Loan() {
    }

    public LoanId id() {
        return loanId;
    }

    public CopyId copyId() {
        return this.copyId;
    }

    public LocalDate expectedReturnDate() {
        return this.expectedReturnDate;
    }

    public LocalDateTime returnedAt() {
        return this.returnedAt;
    }

    public BigDecimal overdueFee() {
        return this.overdueFee;
    }

    public Loan(CopyId copyId, UserId userId, LocalDateTime createdAt) {
        this(copyId, userId, createdAt, createdAt.toLocalDate().plusDays(LOAN_TERM_DAYS));
    }

    public Loan(CopyId copyId, UserId userId, LocalDateTime createdAt, LocalDate expectedReturnDate) {
        Objects.requireNonNull(copyId, "copyId must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(expectedReturnDate, "expectedReturnDate must not be null");
        this.loanId = new LoanId();
        this.copyId = copyId;
        this.userId = userId;
        this.createdAt = createdAt;
        this.expectedReturnDate = expectedReturnDate;
    }

    public void returned(LocalDateTime returnedAt) {
        Objects.requireNonNull(returnedAt, "returnedAt must not be null");
        this.returnedAt = returnedAt;
        if (returnedAt.isAfter(expectedReturnDate.atStartOfDay())) {
            var daysOverdue = ChronoUnit.DAYS.between(expectedReturnDate, returnedAt.toLocalDate());
            var fee = OverdueFee.forDays(daysOverdue);
            this.overdueFee = fee != null ? fee.amount() : null;
            // In production, fire an OverdueFeeCalculated domain event here
        }
    }
}
