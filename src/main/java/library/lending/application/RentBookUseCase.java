package library.lending.application;

import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import library.common.UseCase;
import library.lending.domain.*;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;

@UseCase
public class RentBookUseCase {
    private static final Logger LOGGER = Logger.getLogger(RentBookUseCase.class.getName());
    private LoanRepository loanRepository;
    private CopyAvailabilityValidator copyAvailabilityValidator;
    private Event<LoanCreated> loanCreatedEvent;
    private Clock clock;

    public RentBookUseCase() {
    }

    @Inject
    public RentBookUseCase(LoanRepository loanRepository,
                           CopyAvailabilityValidator copyAvailabilityValidator,
                           Event<LoanCreated> loanCreatedEvent,
                           Clock clock) {
        this.loanRepository = loanRepository;
        this.copyAvailabilityValidator = copyAvailabilityValidator;
        this.loanCreatedEvent = loanCreatedEvent;
        this.clock = clock;
    }

    public void execute(CopyId copyId, UserId userId) {
        copyAvailabilityValidator.checkAvailable(copyId);
        var now = LocalDateTime.now(clock);
        loanRepository.save(new Loan(copyId, userId, now, LocalDate.now(clock).plusDays(30)));

        LOGGER.log(Level.INFO, "firing LoanCreated with copy id = " + copyId);
        loanCreatedEvent.fire(new LoanCreated(copyId));
    }
}
