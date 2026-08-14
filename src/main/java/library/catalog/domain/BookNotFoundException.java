package library.catalog.domain;

import library.common.DomainException;

/**
 * Thrown when a book cannot be found, e.g. a book search by ISBN returned
 * no result or a repository lookup by id came up empty.
 */
public class BookNotFoundException extends DomainException {

    public BookNotFoundException(Isbn isbn) {
        super("book not found for isbn: " + isbn.value());
    }

    public BookNotFoundException(String message) {
        super(message);
    }
}
