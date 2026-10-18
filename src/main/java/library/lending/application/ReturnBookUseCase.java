package library.lending.application;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import library.shared.UseCase;
import library.lending.domain.Loan;
import library.lending.domain.LoanClosed;
import library.lending.domain.LoanId;
import library.lending.domain.LoanRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

@UseCase
public class ReturnBookUseCase {
    private static final Logger LOGGER = Logger.getLogger(ReturnBookUseCase.class.getName());
    private LoanRepository loanRepository;
    private Event<LoanClosed> loanClosedEvent;
    private Clock clock;

    public ReturnBookUseCase() {
    }

    @Inject
    public ReturnBookUseCase(LoanRepository loanRepository,
                             Event<LoanClosed> loanClosedEvent,
                             Clock clock) {
        this.loanRepository = loanRepository;
        this.loanClosedEvent = loanClosedEvent;
        this.clock = clock;
    }

    public void execute(LoanId loanId) {
        Loan loan = loanRepository.findByIdOrThrow(loanId);
        loan.returned(LocalDateTime.now(clock));
        loanRepository.save(loan);

        LOGGER.log(Level.INFO, "firing returned event for loan with id = " + loanId);
        loanClosedEvent.fire(new LoanClosed(loan.copyId()));
    }
}
