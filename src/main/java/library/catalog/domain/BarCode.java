package library.catalog.domain;

import jakarta.persistence.Embeddable;

import java.util.Objects;

@Embeddable
public record BarCode(String code) {
    public BarCode {
        Objects.requireNonNull(code, "Bar code must not be null");
    }
}
