package library.lending.domain;

import jakarta.persistence.Embeddable;

import java.util.Objects;
import java.util.UUID;

@Embeddable
public record CopyId(UUID id) {

    public CopyId {
        Objects.requireNonNull(id, "id must not be null");
    }

// In the lending domain, CopyId should always be copied from the catalog CopyID.
//    public CopyId() {
//        this(UUID.randomUUID());
//    }
}