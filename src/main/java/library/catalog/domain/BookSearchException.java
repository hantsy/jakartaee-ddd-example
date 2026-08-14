package library.catalog.domain;

import library.common.DomainException;

/**
 * Thrown when an external book search (e.g. the Open Library adapter in the
 * infrastructure layer) fails for reasons other than "not found", such as an
 * unexpected upstream status code or a network error.
 */
public class BookSearchException extends DomainException {

    public BookSearchException(String message) {
        super(message);
    }

    public BookSearchException(String message, Throwable cause) {
        super(message, cause);
    }
}
