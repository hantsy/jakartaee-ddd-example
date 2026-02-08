package library.lending.domain;

import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.UUID;

@Embeddable
public record LoanId(UUID id) {

    public LoanId {
        Objects.requireNonNull(id, "id must not be null");
    }

    public LoanId() {
        this(UUID.randomUUID());
    }
}
