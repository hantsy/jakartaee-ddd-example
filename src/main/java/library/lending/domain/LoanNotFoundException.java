package library.lending.domain;

import library.common.DomainException;

/**
 * Thrown when a loan cannot be found, e.g. the loan id passed to
 * {@link LoanRepository#findByIdOrThrow} does not exist.
 */
public class LoanNotFoundException extends DomainException {

    public LoanNotFoundException(LoanId loanId) {
        super("loan with id " + loanId + " was not found");
    }
}
