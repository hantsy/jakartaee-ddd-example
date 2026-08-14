package library.catalog.domain;

import library.common.DomainException;

/**
 * Thrown when a copy cannot be found in the catalog, e.g. a {@code CopyRepository}
 * lookup by id came up empty while reacting to a lending event.
 */
public class CopyNotFoundException extends DomainException {

    public CopyNotFoundException(CopyId copyId) {
        super("copy with id " + copyId + " was not found");
    }
}
