package library.lending.application;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import library.lending.domain.CopyId;
import library.lending.domain.CopyNotAvailableException;
import library.lending.domain.LoanRepository;

@ApplicationScoped
public class CopyAvailabilityValidator {
    private LoanRepository loanRepository;

    CopyAvailabilityValidator() {
    }

    @Inject
    public CopyAvailabilityValidator(LoanRepository loanRepository) {
        this.loanRepository = loanRepository;
    }

    public void checkAvailable(CopyId copyId) {
        if (!loanRepository.isAvailable(copyId)) {
            throw new CopyNotAvailableException(copyId);
        }
    }
}
