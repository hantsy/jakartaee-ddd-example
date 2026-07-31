package library.lending.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class OverdueFeeTest {

    @Test
    void shouldReturnNullWhenNotOverdue() {
        assertThat(OverdueFee.forDays(0)).isNull();
        assertThat(OverdueFee.forDays(-1)).isNull();
    }

    @Test
    void shouldResolveWithinAWeek() {
        var fee = OverdueFee.forDays(1);
        assertThat(fee).isEqualTo(OverdueFee.WITHIN_A_WEEK);
        assertThat(fee.amount()).isEqualTo(new BigDecimal("5.00"));

        fee = OverdueFee.forDays(7);
        assertThat(fee).isEqualTo(OverdueFee.WITHIN_A_WEEK);
    }

    @Test
    void shouldResolveWithinTwoWeeks() {
        var fee = OverdueFee.forDays(8);
        assertThat(fee).isEqualTo(OverdueFee.WITHIN_TWO_WEEKS);
        assertThat(fee.amount()).isEqualTo(new BigDecimal("10.00"));

        fee = OverdueFee.forDays(14);
        assertThat(fee).isEqualTo(OverdueFee.WITHIN_TWO_WEEKS);
    }

    @Test
    void shouldResolveWithinAMonth() {
        var fee = OverdueFee.forDays(15);
        assertThat(fee).isEqualTo(OverdueFee.WITHIN_A_MONTH);
        assertThat(fee.amount()).isEqualTo(new BigDecimal("20.00"));

        fee = OverdueFee.forDays(30);
        assertThat(fee).isEqualTo(OverdueFee.WITHIN_A_MONTH);
    }

    @Test
    void shouldResolveBeyondAMonth() {
        var fee = OverdueFee.forDays(31);
        assertThat(fee).isEqualTo(OverdueFee.BEYOND_A_MONTH);
        assertThat(fee.amount()).isEqualTo(new BigDecimal("50.00"));

        fee = OverdueFee.forDays(365);
        assertThat(fee).isEqualTo(OverdueFee.BEYOND_A_MONTH);
    }
}
