package library.catalog.domain;

import jakarta.persistence.Embeddable;
import org.apache.commons.validator.routines.ISBNValidator;

@Embeddable
public record Isbn(String value) {
    private static final ISBNValidator VALIDATOR = ISBNValidator.getInstance();

    public Isbn {
        if (!VALIDATOR.isValid(value)) {
            throw new IllegalArgumentException("invalid isbn: " + value);
        }
    }
}
