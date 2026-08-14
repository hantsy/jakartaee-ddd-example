package library.common;

/**
 * Base type for all domain exceptions in the Library application.
 * <p>
 * Extending {@link RuntimeException} means that when a domain exception is thrown
 * from a {@code @Transactional} use case, the container rolls back the transaction.
 * Concrete subclasses live inside each bounded context's {@code domain} package,
 * so callers (application services, infrastructure adapters, REST resources)
 * can react to typed failures instead of generic {@link IllegalArgumentException},
 * {@link IllegalStateException} or {@link java.util.NoSuchElementException}.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }
}
