package library.catalog.domain;

import library.common.DomainException;

/**
 * Thrown when a book with the same ISBN is already registered in the catalog.
 * The ISBN is the natural business key of a {@link Book}.
 */
public class BookAlreadyExistsException extends DomainException {

    public BookAlreadyExistsException(Isbn isbn) {
        super("book with isbn " + isbn.value() + " already exists");
    }
}
