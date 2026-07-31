package library.lending.domain;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

/**
 * Dummy overdue fee schedule for demonstration purposes.
 * <p>
 * In a real-world system this data would be stored in a database table
 * (e.g. {@code overdue_fee_tier}) so the library staff can adjust rates
 * without redeploying, and the lookup would be a repository call.
 */
public enum OverdueFee {

    WITHIN_A_WEEK(7, new BigDecimal("5.00")),
    WITHIN_TWO_WEEKS(14, new BigDecimal("10.00")),
    WITHIN_A_MONTH(30, new BigDecimal("20.00")),
    BEYOND_A_MONTH(Integer.MAX_VALUE, new BigDecimal("50.00"));

    private final int maxDays;
    private final BigDecimal amount;

    OverdueFee(int maxDays, BigDecimal amount) {
        this.maxDays = maxDays;
        this.amount = amount;
    }

    /**
     * Resolves the fee for the given number of overdue days.
     *
     * @param daysOverdue days past the expected return date
     * @return the matching fee tier, or {@code null} if not overdue
     */
    public static OverdueFee forDays(long daysOverdue) {
        if (daysOverdue <= 0) {
            return null;
        }
        for (var tier : values()) {
            if (daysOverdue <= tier.maxDays) {
                return tier;
            }
        }
        return BEYOND_A_MONTH;
    }

    public BigDecimal amount() {
        return amount;
    }
}
