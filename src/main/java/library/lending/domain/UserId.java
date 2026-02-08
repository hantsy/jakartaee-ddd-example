package library.lending.domain;

import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.UUID;

@Embeddable
public record UserId(UUID id) {

    public UserId {
        Objects.requireNonNull(id, "id must not be null");
    }

    public UserId() {
        this(UUID.randomUUID());
    }
}