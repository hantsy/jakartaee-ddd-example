---
title: "Applying DDD with Jakarta EE — Strategic and Tactical Design with Jakarta Data"
date: 2026-10-08
tags: [jakarta-ee, jakarta-data, ddd, event-storming, strategic-design, tactical-design, jpa, cdi, archunit]
repo: https://github.com/hantsy/jakartaee-ddd-example
status: draft
---

# Applying DDD with Jakarta EE: Strategic and Tactical Design with Jakarta Data

Domain-Driven Design (DDD) helps teams build software around a shared model of a
business domain. This article is for Java developers who want to see how DDD
concepts shape a Jakarta EE application. It follows a small library example from
requirements through design, implementation, and tests.

You should be comfortable with Java and basic Jakarta EE. You don't need prior
DDD experience. If DDD is new to you, start with Martin Fowler's
[Domain-Driven Design overview](https://martinfowler.com/bliki/DomainDrivenDesign.html)
or the
[introduction by Dan Haywood](https://www.dddcommunity.org/learning-ddd/haywood_2010/)
listed by the DDD Community. This article focuses on one sample; it isn't a
complete guide to DDD or a production-ready library system.

Strategic design helps you understand the domain, agree on its language, and
choose model boundaries. Tactical design applies those decisions in code, using
building blocks such as entities, value objects, aggregates, repositories, and
domain events. These activities inform each other; they aren't separate phases
that you complete once. Jakarta EE provides infrastructure for implementing
these patterns, and Jakarta Data lets you declare repository interfaces instead
of writing common persistence implementations by hand.

Parts 0 and I introduce the library requirements and design without framework
code. Part II maps those decisions to Jakarta EE. Part III compares the
requirements with the tests, and Part IV shows how to build and run the sample.

The example is
[`jakartaee-ddd-example`](https://github.com/hantsy/jakartaee-ddd-example), a
library system with cataloging and lending contexts. It adapts the example from
Maciej Walkowiak's
[Spring IO 2024 talk, *Implementing Domain-Driven Design with Spring*](https://www.youtube.com/watch?v=VGhg6Tfxb60)
([source code](https://github.com/maciejwalkowiak/implementing-ddd-with-spring-talk))
to Jakarta EE 11.

---

## Part 0 — Requirements

The requirements use a short story and an event-storming summary. The story
provides context; the event storm identifies commands, events, policies, and
actors. Numbered scenarios make it possible to compare the requirements with the
tests in Part III.

### 0.1 The story

> It is Monday morning and the library has just received a new shipment. Priya,
> the cataloging librarian, picks up *Effective Java* and types its ISBN into
> the desk terminal. She does not type the title — the system looks it up in the
> external bibliographic service and fills it in. If the number is wrong, or the
> book is already on the catalog, the system tells her and changes nothing. She
> then sticks four barcodes on four physical copies and registers each one. All
> four go on the shelf as available.
>
> At lunchtime Sam borrows one of the four. The desk scans the barcode, and a
> loan opens for thirty days. That copy immediately leaves the shelf — if Priya
> looks at the catalog she will see three available, not four. If Sam's friend
> tries to borrow the same physical copy a minute later, the desk refuses: that
> copy is out. The other three are unaffected.
>
> Six weeks later Sam brings it back. The desk closes the loan and the copy goes
> back on the shelf. Because it is almost two weeks late, a fee is charged —
> and the amount depends on how late it is. A week late costs less than a month
> late. The fee belongs to that loan, not to Sam as a person and not to the copy:
> the same copy returned on time by another borrower costs nothing.
>
> Meanwhile the other three copies have been borrowed and returned a dozen times
> each. The catalog entry for *Effective Java* has not changed once.

Three details guide the design:

- Priya never retypes a title, and a failed lookup leaves no half-created entry.
- The shelf and the loans are two different concerns that must nonetheless agree.
- A fee is a property of one loan, decided once, at the moment of return.

### 0.2 The event storm

The diagram uses a text-only version of Alberto Brandolini's event-storming
notation:

```
  (actor)          person who does something
  [blue]  COMMAND  imperative — someone wants something to happen
  [orange] EVENT   past tense — it happened, it cannot be undone
  [violet] POLICY  "whenever <event>, then <command>"
  [green]  READ    information a person looks at to decide what to do
  [pink]   EXTERNAL a system outside our control
  «AGGREGATE»      the thing that decides whether a command is allowed
```

**Cataloging journey**

```
(Librarian) ─▶ [Catalog Book] ─▶ «BOOK» ─▶ [Book Cataloged] ─▶ (Catalog listing)
                     │
                     └─▶ [Resolve Title] ─▶ (BIBLIOGRAPHIC SERVICE)
                                                │
                                    ┌───────────┴───────────┐
                             [Title Resolved]        [Lookup Failed]

(Librarian) ─▶ [Register Copy] ─▶ «COPY» ─▶ [Copy Registered] ─▶ (Shelf status)
```

**Lending journey**

```
(Borrower) ─▶ [Lend Copy] ─▶ «LOAN» ─▶ [Loan Opened] ─▶ (Borrower's loans)
                                          │
                                          └─▶ POLICY: whenever a loan opens,
                                              take that copy off the shelf
                                                  │
                                                  └─▶ «COPY» ─▶ [Copy Off Shelf]

(Desk staff) ─▶ [Return Copy] ─▶ «LOAN» ─▶ [Loan Closed]
                                          │
                                          ├─▶ POLICY: whenever a loan closes,
                                          │   put that copy back on the shelf
                                          │       └─▶ «COPY» ─▶ [Copy On Shelf]
                                          │
                                          └─▶ POLICY: whenever a loan closes late,
                                              charge the tier that matches the delay
                                                  └─▶ [Overdue Fee Charged]
```

#### The storm, tabulated

**Domain events** — what actually happened, in the past tense.

| Event | Raised by | Who cares |
| --- | --- | --- |
| Book Cataloged | the book | cataloging staff, the catalog listing |
| Copy Registered | the copy | the shelf |
| Loan Opened | the loan | the borrower, and the shelf |
| Loan Closed | the loan | the desk, the shelf, and billing |
| Copy Off Shelf / Copy On Shelf | the copy | anyone looking at availability |
| Overdue Fee Charged | the loan | billing — *identified, not yet emitted* |

**Commands** — what people ask for.

| Command | Actor | Aggregate that decides |
| --- | --- | --- |
| Catalog Book | Librarian | Book |
| Register Copy | Librarian | Copy |
| Lend Copy | Borrower | Loan |
| Return Copy | Desk staff | Loan |

**Policies** — the automatic reactions. These are where the two halves of the
library meet.

| Whenever | Then |
| --- | --- |
| A book is cataloged and its ISBN is already present | Refuse; change nothing |
| A title cannot be resolved | Refuse; leave the catalog untouched |
| A loan opens | Take that copy off the shelf |
| A loan closes | Put that copy back on the shelf |
| A loan closes after its due date | Charge the matching tier |

**Read models** — Catalog listing, Shelf status, and Borrower's loans are
views the users need. This sample identifies those queries but doesn't implement
separate read models or event-fed projections.

**External system** — the Bibliographic Service. It sits outside the library's
control, so the model puts it behind a dedicated title-resolution command.

### 0.3 Journeys and acceptance scenarios

The numbered IDs in the following scenarios are what Part III's coverage
matrix refers back to.

#### J1 — Catalog a book by ISBN

| # | Scenario | What must happen |
| --- | --- | --- |
| 1.1 | The number typed is not a valid ISBN | Refused on the spot. Nothing is added; the librarian is told the number is wrong. |
| 1.2 | The book is already in the catalog | Refused as a duplicate. The existing entry is untouched — no second record, no overwrite. |
| 1.3 | The ISBN is valid and new | The title is resolved from the bibliographic service and a new catalog entry is created. |
| 1.4 | The bibliographic service has never heard of the ISBN | The librarian is told the book could not be found. Nothing is added — an entry with no title is worse than no entry. |
| 1.5 | The bibliographic service is failing | The librarian is told the lookup failed and may retry later. Nothing is added. |
| 1.6 | The network is down | As 1.5. |

**Business rule.** Cataloging a book is all-or-nothing. If the title cannot be
resolved, no partial entry is left behind for someone to clean up later.

#### J2 — Register a physical copy

| # | Scenario | What must happen |
| --- | --- | --- |
| 2.1 | A valid book and barcode | A new copy exists, on the shelf, available to borrow. |
| 2.2 | Either the book or the barcode is missing | Refused. A copy must belong to a book and must be identifiable at the desk. |

**Business rule.** A copy can only be registered against a book that is already
in the catalog. *Whether the system enforces this is a separate question — see
Part III.*

#### J3 — Lend a copy

| # | Scenario | What must happen |
| --- | --- | --- |
| 3.1 | The copy is on the shelf | A loan opens, due in thirty days. The copy leaves the shelf. |
| 3.2 | The copy is already out | Refused. The borrower is told the copy is unavailable. No second loan opens. |
| 3.3 | Two borrowers ask for the same copy at the same instant | Exactly one loan opens; the other is refused. |

**Business rules.** A copy is on at most one open loan at a time. The due date is
always thirty days out. Lending is atomic — either the loan exists *and* the copy
is off the shelf, or neither is true.

#### J4 — Return a copy

| # | Scenario | What must happen |
| --- | --- | --- |
| 4.1 | The loan is open | The loan closes, the return date is recorded, the copy is back on the shelf. |
| 4.2 | The loan is not recognized | Refused. The desk cannot close a loan it cannot find. |
| 4.3 | Returned on or before the due date | No fee. |
| 4.4 | Returned after the due date | A fee is charged, per J5. |

#### J5 — The overdue fee

The library charges a flat fee based on the number of days a copy is overdue:

| Days late | Fee |
| --- | --- |
| Not late | — |
| 1 – 7 | 5.00 |
| 8 – 14 | 10.00 |
| 15 – 30 | 20.00 |
| More than 30 | 50.00 |

**Business rules.** The fee is a flat tier amount, not a per-day accrual. It is
decided once, at the moment of return. It belongs to the loan — not to the copy,
and not to the borrower.

---

## Part I — Strategic design

Strategic design uses conversations with domain experts to understand the
business, define the model, and identify its boundaries. Teams revisit these
decisions as they learn more; strategic design doesn't have to finish before
implementation begins. This section focuses on the domain rather than on
framework details.

### 1. Subdomain classification

DDD distinguishes three kinds of subdomain. The classification helps a team
decide where to invest in custom software and where to use an existing solution:

- **Core:** the part of the business that provides its main competitive
  advantage. A team usually invests in a model tailored to its needs.
- **Supporting:** necessary for the business, but not a source of competitive
  advantage. A team can build a focused solution or adapt an existing one.
- **Generic:** a common problem with established solutions. A team can buy,
  adopt, or integrate a standard solution instead of building one from scratch.

These classifications depend on the organization. For this example, assume the
library treats lending as its core subdomain, cataloging as supporting, and
bibliographic lookup as generic:

| Subdomain | Class | Rationale in this example | Design consequence |
| --- | --- | --- | --- |
| **Lending** | **Core** | Loan duration, availability, and fees are library-specific policies. | Model and test these rules carefully. |
| **Cataloging** | **Supporting** | The library needs accurate records of books and physical copies. | Keep cataloging focused on the requirements in this example. |
| **Bibliographic lookup** | **Generic** | External services can resolve ISBNs to bibliographic information. | Integrate through a replaceable interface rather than building a lookup service here. |

This classification helps prioritize design effort. Lending has the most
business-specific policies in this example, so the model focuses on loan
availability and overdue fees. The bibliographic lookup sits behind an
interface, which makes the provider easier to replace.

The example doesn't include user management. A borrower appears only as an
identifier on a loan; authentication, profiles, and notifications are outside
its scope. A real team would classify those capabilities based on its own
business and existing systems.

A subdomain partitions the business problem; a bounded context defines where a
particular model and its language apply. They can align, but they don't have to.
Here, the bibliographic lookup subdomain has no separate context. Cataloging
owns the interface to the external provider.

### 2. Ubiquitous language

The team uses the following terms consistently in its conversations,
requirements, and code:

| Term | What it means here |
| --- | --- |
| **Book** | A catalog record identified by an ISBN. It isn't a physical item. |
| **Title** | The published name of a book. |
| **ISBN** | A number that identifies a particular book edition and format; this sample uses it to find a catalog record. |
| **Copy** | One physical item on the shelf — a specific book, with its own barcode. |
| **Barcode** | The sticker on a copy, scanned at the desk. |
| **Shelf status** | Whether a copy can be borrowed. |
| **Borrower** | A library member identified on a loan. |
| **Loan** | The record that a borrower holds a copy — opened on one date, due on another. |
| **Due date** | Thirty days after a loan opens. |
| **Return** | Closing an open loan. |
| **Overdue** | A loan returned after its due date. |
| **Overdue fee** | A tiered charge for lateness. |
| **Catalog** | The library's list of the books it holds. |

The model distinguishes a catalog record from each physical item:
**a book isn't a copy.** A library can hold one catalog record for
*Domain-Driven Design* and four physical copies. Each copy has its own barcode
and availability.

The glossary also clarifies two terms that could otherwise be ambiguous:

- **Available:** In this example, a copy is available when it has no open loan.
- **Return:** Returning a copy closes a loan. The return date is recorded
  separately and determines whether the loan is overdue.

Part II shows where these terms appear in the code. Each context has its own
`CopyId` type to make the context boundary explicit.

### 3. Bounded contexts

The example separates cataloging and lending because they model different
concerns:

**Cataloging** models the library's holdings: which books it records, which
physical copies belong to them, and whether each copy is available.

**Lending** models who has a copy and when it is due. It handles loan
availability, return dates, and overdue fees.

These concerns suggest separate bounded contexts in this example:

1. **The word "copy" means different things to them.** To cataloging, a copy is
   a physical asset with a barcode and a shelf status. Lending stores only a
   copy identifier on a loan. The same word appears in both contexts, but each
   context uses a different model.
2. **They change for different reasons.** A fee-tier change affects lending; a
   title correction affects cataloging. Keeping the models separate limits
   unnecessary dependencies between those changes.
3. **They could have different owners.** A larger system might assign different
   teams, storage, or scaling requirements to each context.

The `library.shared` package contains cross-cutting technical types, including
the `@UseCase` stereotype, `DomainException`, and the clock producer. It is a
shared technical package, not a DDD shared kernel: it doesn't contain a shared
part of the cataloging or lending domain model.

### 4. Context map

The contexts exchange copy identity and loan events in opposite directions.
These are in-process relationships in this sample, not independently deployed
services.

**Lending depends on cataloging for identity.**

Lending must be able to say *which* copy is on loan, so it stores a copy
identifier. Cataloging owns that identity; lending represents it with its own
`CopyId` type.

Lending doesn't model barcodes or update cataloging's availability flag.

**Cataloging depends on lending for facts.**

Cataloging updates a copy's availability flag when it receives a loan event.
When lending announces that a loan opened, the listener marks the copy
unavailable; when lending announces that a loan closed, it marks the copy
available.

`DomainEventListener` is the cataloging class that imports lending event types.
The contexts don't read each other's database tables, but they do share event
types. CDI delivers those events synchronously in this application. If you
deploy the contexts as separate services, design message delivery, retries, and
consistency guarantees; CDI events don't provide those guarantees.

### 5. What the strategy commits us to

These design choices guide the implementation:

1. **Three subdomains.** In this example, lending is core, cataloging is
   supporting, and bibliographic lookup is generic.
2. **Two bounded contexts and a shared technical package.** Cataloging and
   lending use separate models and exchange identifiers and announcements.
3. **One language per context.** Cataloging models a physical copy; lending
   refers to that copy by an identifier.
4. **A context map with explicit dependencies.** Lending stores copy identities;
   cataloging subscribes to lending's events. `DomainEventListener` owns the
   event dependency in cataloging.
5. **Cataloging updates shelf status** when it receives a lending event.
6. **The generic subdomain sits behind a boundary cataloging owns**, so the
   provider can be swapped or bought without touching the model.

Part II applies these choices to entities, value objects, aggregate boundaries,
repositories, and domain events.

---

## Part II — Tactical design with Jakarta EE

Tactical design represents the domain model in code. The example uses entities,
value objects, aggregates, repositories, domain events, domain services, and
application services. Jakarta EE supplies infrastructure mechanisms for these
patterns; Jakarta Data provides the repository API used in this sample.

### From design decisions to code

The following table maps the design choices from Part I to their implementation:

| Strategic commitment | Where it lands | Mechanism |
| --- | --- | --- |
| 1. Keep bibliographic lookup replaceable | `BookSearchService` → `OpenLibraryBookSearchService` | Domain port and CDI adapter |
| 2. Keep the contexts' models separate | `library.catalog..` / `library.lending..` | Package boundaries enforced by ArchUnit rules 6 and 7 |
| 3. Use a context-specific copy identifier | `catalog.domain.CopyId` and `lending.domain.CopyId` | Separate types; lending consumes the catalog identity |
| 4. Keep event coupling in one place | `DomainEventListener` | ArchUnit rule 7 allows this dependency |
| 5. Update shelf status from loan events | `Copy.available` | CDI observer methods handle `LoanCreated` and `LoanClosed` |
| 6. Keep the external adapter out of application code | `library.catalog.infrastructure` | ArchUnit rule 3 prevents application-to-infrastructure dependencies |

And the tactical building blocks themselves:

| Building block | In this sample | Jakarta EE mechanism |
| --- | --- | --- |
| Entity / aggregate root | `Book`, `Copy`, `Loan` | JPA `@Entity`, `@EmbeddedId`, `@Version` |
| Value object | `Isbn`, `BarCode`, every `*Id` | Java `record` + `@Embeddable` |
| Repository | `BookRepository`, `CopyRepository`, `LoanRepository` | **Jakarta Data** `@Repository` + `CrudRepository` |
| Domain event | `LoanCreated`, `LoanClosed` | **CDI** `Event<T>` / `@Observes` |
| Domain service / port | `CopyAvailabilityValidator`, `BookSearchService` | CDI bean / domain-facing interface |
| Application service | the four `*UseCase` classes | `@UseCase` stereotype |
| Transaction & rollback | every use case | JTA `@Transactional` + unchecked `DomainException` |
| Adapter | `OpenLibraryBookSearchService` | CDI `@ApplicationScoped` + JAX-RS `Client` |
| Cross-cutting policy | logging | CDI `@Interceptor` + `@InterceptorBinding` |
| Time as a dependency | loan dates | CDI `@Produces` `Clock` |
| Persistence config | `defaultPU` → `java:comp/DefaultDataSource` | `persistence.xml`, JTA datasource |
| Bean discovery | interceptor registration | `beans.xml` 4.0 |

The building blocks in the second table each get a section in Part II.

### 1. Entities, value objects, and aggregate roots with JPA

#### Which concepts are entities and which are values

Ask whether the model needs to distinguish two instances that have the same
attributes. If not, they can be values. If the model tracks each instance
separately as it changes, it is an entity.

**Entities.** The model has three entity types, each serving as an aggregate
root:

- **Book** — a catalog record for a book edition. Its identity remains the
  same if the title or other details change.
- **Copy** — a physical item with its own identity and barcode. A library can
  hold several copies of one book; each copy has its own availability.
- **Loan** — a record with its own lifecycle. It opens, remains open until the
  copy is returned, and stores its return date and any overdue fee.

**Value objects.** The model represents the following concepts as values:

- **ISBN** and **BarCode** — immutable values that identify a book edition and a
  physical copy.
- **Identifiers** — values used to refer to books, copies, borrowers, and loans.
- **A bibliographic lookup result** — a snapshot of the information returned by
  the external service.
- **A fee tier** — a value representing the amount charged for a range of
  overdue days.
- **Loan events** — immutable records of facts such as a loan opening or closing.

This model uses three entity types and represents several other concepts as
values. This is a choice for the example, not a general target ratio for DDD
models.

#### Aggregate boundaries

An aggregate is a consistency boundary with one root. The root protects the
aggregate's invariants when the application changes its state. Invariants that
cross aggregate boundaries need a separate consistency strategy.

In this example, the boundaries exclude related objects:

**A copy does not contain its book.** A copy stores the book's identifier. This
keeps copy registration and book updates in separate aggregates.

**A loan does not contain a copy or a borrower.** A loan is a lending fact. It
stores the copy and borrower identifiers. Lending doesn't own the cataloging
copy or update its barcode.

The example follows this rule:

> **Aggregates refer to each other by identity, never by containment.**

Keeping aggregate references as identifiers helps transactions update one
aggregate at a time. It doesn't by itself enforce cross-aggregate invariants such
as allowing only one open loan per copy.

#### Invariants and their guards

| Invariant | Guarded by | Current behavior |
| --- | --- | --- |
| An ISBN must be structurally valid | `Isbn` constructor | Enforced |
| No duplicate book in the catalog | `AddBookToCatalogUseCase` checks before adding | Checked before each add |
| A copy must have a book ID and barcode | `Copy` constructor | Enforced |
| A copy's book ID must identify a cataloged book | Not enforced in the current code | Not enforced |
| At most one open loan per copy | Availability check before lending | Checked for sequential requests; concurrency isn't tested |
| The due date is thirty days out | `Loan` constructor | Enforced |
| The fee tier follows the days late | `Loan.returned` | Enforced |
| Shelf status matches loan state | Synchronous CDI observer | Updated in the application transaction |

The table distinguishes checks the current implementation performs from a
requirement it doesn't enforce. The availability check prevents a second loan
for sequential requests; the sample doesn't include a concurrent-rental test.
The synchronous CDI observer updates shelf status in the same application
transaction. Separate services would need a different consistency strategy.

#### Map the model with JPA

Value objects keep the entity mapping focused on the domain model:

```java
@Entity
public class Book {
    @EmbeddedId
    private BookId id;
    private String title;
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "isbn"))
    private Isbn isbn;

    Book() { }

    public Book(String title, Isbn isbn) {
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(isbn, "isbn must not be null");
        this.id = new BookId();
        this.title = title;
        this.isbn = isbn;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return Objects.equals(id, ((Book) o).id);
    }

    @Override
    public int hashCode() { return Objects.hashCode(id); }
}
```

The details that matter:

- **The no-arg constructor is package-private in this sample.** The Jakarta
  Persistence specification requires a public or protected no-arg constructor
  for an entity. Although the providers used by this project accept the
  package-private constructor, use `protected` if you want to follow the
  specification and improve portability.
- **`equals`/`hashCode` compare identity only.** `Book` does not compare title
  or ISBN. That is what "entity" means, and getting it wrong is a classic
  source of `Set` corruption in JPA.
- **No setters.** `Copy` exposes `makeAvailable()` / `makeUnavailable()`, never
  `setAvailable(boolean)`. The method name carries the intent, and callers
  cannot put a copy into an arbitrary state for an arbitrary reason.
- **`@AttributeOverride`** disambiguates the column when the same embeddable
  type appears twice (`Loan` holds both a `CopyId` and a `UserId`, and both
  records wrap a field named `id`).

Records work as `@Embeddable` value objects with the providers tested by this
project: EclipseLink 5.0.2 (GlassFish) and Hibernate 7.4 (WildFly Preview).

### 2. Repositories with Jakarta Data

Jakarta Data provides repository interfaces that a Jakarta EE runtime implements.

**The entire persistence layer for the catalog context:**

```java
package library.catalog.domain;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Query;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

@Repository
@Transactional
public interface BookRepository extends CrudRepository<Book, BookId> {

    @Query("select count(*) > 0 from Book where isbn = :isbn")
    boolean existsByIsbn(Isbn isbn);
}
```

```java
@Repository
@Transactional
public interface CopyRepository extends CrudRepository<Copy, CopyId> {
}
```

`CopyRepository` is four lines and contains no members. It still gives you the
whole `CrudRepository` contract — `save`, `saveAll`, `insert`, `insertAll`,
`update`, `updateAll`, `delete`, `deleteAll`, `deleteById`, `findById`,
`findAll()`, and `findAll(PageRequest, Order)`.

**There is no implementation class anywhere in the source tree.** You inject the
interface like any other CDI bean:

```java
@Inject private BookRepository bookRepository;
```

#### How the runtimes implement the repository

The distinction between the API and its implementation is important:

> **Jakarta Data is a specification, not a library.** There is no
> `jakarta-data-impl.jar` you add to your POM. `jakarta.jakartaee-api` is
> `<scope>provided</scope>` — it hands you the annotations and interfaces at
> compile time and nothing else. The implementation is supplied by the
> **runtime**, which means the same four-line interface is realized by two
> completely different mechanisms depending on which server you deploy to.

This project is wired to demonstrate exactly that. The POM swaps the annotation
processor per profile:

| Profile | Server | Persistence provider | `annotationProcessorPaths` |
| --- | --- | --- | --- |
| *(default)* `glassfish` | GlassFish 8.0.5 | EclipseLink 5.0.2 | `org.eclipse.persistence:org.eclipse.persistence.jpa.modelgen.processor` |
| `wildfly` | WildFly Preview 41.0.1.Final | Hibernate 7.4.11.Final | `org.hibernate.orm:hibernate-processor` |

Running the same goal under each profile and listing what lands in
`target/generated-sources/annotations` makes the difference visible:

```console
$ mvn -B compiler:compile                      # default → EclipseLink processor
library/catalog/domain/  BarCode_.java  BookId_.java  Book_.java
                         CopyId_.java   Copy_.java    Isbn_.java
library/lending/domain/  CopyId_.java  LoanId_.java  Loan_.java  UserId_.java

$ mvn -B -P wildfly compiler:compile           # → hibernate-processor
library/catalog/domain/  BarCode_.java  BookId_.java  Book_.java
                         CopyId_.java   Copy_.java    Isbn_.java
                         BookRepository_.java   CopyRepository_.java   <-- new
library/lending/domain/  CopyId_.java  LoanId_.java  Loan_.java  UserId_.java
                         LoanRepository_.java                          <-- new
```

**EclipseLink generates no repository implementation at all.** Only the JPA
static metamodel. The interface goes into the WAR with nothing beside it, and
EclipseLink builds an implementation at *deployment* time — reflectively, as a
proxy — then registers it as a CDI bean.

**Hibernate generates a real class at compile time.** Here is the actual output
for `BookRepository`, lightly trimmed:

```java
@Dependent
@Generated("org.hibernate.processor.HibernateProcessor")
@jakarta.transaction.Transactional
public class BookRepository_ implements BookRepository {

    /** @see #existsByIsbn(Isbn) */
    static final String EXISTS_BY_ISBN_Isbn = "select count(*) > 0 from Book where isbn = :isbn";

    protected @Nonnull StatelessSession session;

    @PersistenceUnit
    private EntityManagerFactory sessionFactory;

    @PostConstruct
    private void openSession() {
        session = sessionFactory.unwrap(SessionFactory.class).openStatelessSession();
    }

    @PreDestroy
    private void closeSession() { session.close(); }

    @Inject
    BookRepository_() { }

    @Override
    public <S extends Book> S save(@Nonnull S entity) {
        requireNonNull(entity, "Null entity");
        try {
            session.upsert(entity);
        } catch (StaleStateException _ex) {
            throw new OptimisticLockingFailureException(_ex.getMessage(), _ex);
        } catch (PersistenceException _ex) {
            throw new DataException(_ex.getMessage(), _ex);
        }
        return entity;
    }

    @Override
    public boolean existsByIsbn(Isbn isbn) {
        try {
            return session.createSelectionQuery(EXISTS_BY_ISBN_Isbn, boolean.class)
                          .setParameter("isbn", isbn)
                          .getSingleResult();
        } catch (NoResultException _ex) {
            throw new EmptyResultException(_ex.getMessage(), _ex);
        } catch (NonUniqueResultException _ex) {
            throw new jakarta.data.exceptions.NonUniqueResultException(_ex.getMessage(), _ex);
        } catch (PersistenceException _ex) {
            throw new DataException(_ex.getMessage(), _ex);
        }
    }

    @Override
    public Optional<Book> findById(@Nonnull BookId id) {
        requireNonNull(id, "Null id");
        return ofNullable(session.get(Book.class, id));
    }
    // findAll(), findAll(PageRequest, Order), deleteById(BookId), insert*/update*/delete* …
}
```

Seven things in that output are worth noticing:

1. **It is a concrete class in the same package as the interface**, CDI
   `@Dependent`-scoped, and stamped `@Generated("org.hibernate.processor.HibernateProcessor")`.
2. **Lifecycle is generated, not inherited.** A `@PersistenceUnit
   EntityManagerFactory` is unwrapped to a Hibernate `SessionFactory` and a
   `StatelessSession` is opened in `@PostConstruct`, closed in `@PreDestroy`.
   *Stateless* is the right choice for a repository: no persistence context, no
   first-level cache, detached results.
3. **`save()` is `session.upsert()`** — the insert-or-update semantics Jakarta
   Data promises, mapped onto the provider's primitive.
4. **The `@Query` text is hoisted into a `static final` constant** and compiled
   into `createSelectionQuery(…)`. Your JPQL becomes a typed, parameterised
   Hibernate query at build time.
5. **Exception translation is generated too.** `ConstraintViolationException` →
   `EntityExistsException`, `StaleStateException` →
   `OptimisticLockingFailureException`, `NoResultException` →
   `EmptyResultException`, `PersistenceException` → `DataException`. Every
   provider-specific failure becomes a `jakarta.data.exceptions.*` type — the
   same anti-corruption discipline the sample applies by hand in
   `OpenLibraryBookSearchService` (§4.3), here done for you.
6. **Pagination is already implemented.** `findAll(PageRequest, Order)` builds a
   criteria query, applies sorts via `SelectionSpecification`, counts only when
   `pageRequest.requestTotal()` asks it to, and returns a `PageRecord`.
7. **Default methods remain available.** The generated class implements the
   interface, so it inherits `LoanRepository.findByIdOrThrow` without generated
   code.

#### Compare the implementation approaches

| Aspect | Compile-time code generation (Hibernate / WildFly) | Runtime reflection (EclipseLink / GlassFish) |
| --- | --- | --- |
| Implementation | a `.java`/`.class` you can read and step into | an opaque proxy |
| Build requirement | processor on `annotationProcessorPaths` | nothing extra |
| Deployment cost | none — the class already exists | reflection and proxy construction at deploy |
| Debugging | breakpoints in generated source | breakpoints in a proxy |
| Provider coupling | generated class imports `org.hibernate.StatelessSession` | none in your build output |
| Where problems surface | at compile/deploy, earlier | at deploy or first invocation |

The coupling row matters: `BookRepository_` is Hibernate-specific, but
`BookRepository` isn't. The application depends on the interface, while each
runtime supplies its own implementation. The project tests the same source tree
with both servers.

Two practical consequences:

- If you build with the WildFly profile and then deploy that WAR to GlassFish,
  you ship Hibernate-specific generated classes into an EclipseLink runtime.
  Build per target, or keep the generated sources out of the artifact.
- The Hibernate processor emits `@Transactional` on the generated class. The
  interface annotation also applies when EclipseLink supplies its runtime
  implementation.

#### Replace a hand-written repository

Without a repository implementation supplied by the runtime, a project can write
an adapter like this:

```java
// the file you no longer need
@ApplicationScoped
public class JpaBookRepository implements BookRepository {
    @PersistenceContext private EntityManager em;

    public Book save(Book book) {
        if (em.find(Book.class, book.getId()) == null) {
            em.persist(book);
            return book;
        }
        return em.merge(book);
    }
    public Optional<Book> findById(BookId id) {
        return Optional.ofNullable(em.find(Book.class, id));
    }
    public boolean existsByIsbn(Isbn isbn) {
        return em.createQuery("select count(*) > 0 from Book where isbn = :isbn", Boolean.class)
                 .setParameter("isbn", isbn).getSingleResult();
    }
    // …and the same shape again for every other aggregate
}
```

This implementation translates repository calls into JPA operations. Jakarta
Data removes the need to maintain that adapter in this sample, while the
repository interface remains part of the application contract.

#### Practical repository patterns

**a) `@Query` returns the domain's type, not the database's.**

```java
@Repository
@Transactional
public interface LoanRepository extends CrudRepository<Loan, LoanId> {

    @Query("select count(*) = 0 from Loan where copyId = :id and returnedAt is null")
    boolean isAvailable(CopyId id);

    default Loan findByIdOrThrow(LoanId loanId) {
        return findById(loanId).orElseThrow(() -> new LoanNotFoundException(loanId));
    }
}
```

`existsByIsbn` and `isAvailable` return `boolean`, not a `Long` you compare
against zero at every call site. The predicate lives in the query where it
belongs.

**b) A `default` method turns a generic API into a domain API.**

`CrudRepository.findById` returns `Optional<Loan>` — correct, but it pushes the
"what if it is missing?" decision onto every caller. `findByIdOrThrow` decides
once, and decides *in domain terms*: a missing loan is a `LoanNotFoundException`,
which extends the shared base:

```java
public abstract class DomainException extends RuntimeException { … }
```

Because it is unchecked, throwing it out of a `@Transactional` use case rolls the
transaction back automatically — no `@ApplicationException` needed. That is the
entire purpose of the base class: callers react to typed failures instead of
parsing `Optional.empty()` or catching a generic `NoSuchElementException`. And
it needs no separate implementation — it is an interface default method.

**c) The repository interface lives in `..domain..`.**

This feels wrong the first time. In hexagonal terms the repository is a **port**:
the domain declares what it needs ("store and retrieve books") and the platform
supplies the adapter. Jakarta Data makes the adapter invisible, so only the port
remains — and a port belongs to the domain.

It is not left to convention:

```java
@Test
void repositoryContractsMustResideInDomainLayer() {
    ArchRule rule = classes().that().haveSimpleNameEndingWith("Repository")
            .should().resideInAPackage("..domain..")
            .because("a Repository is a domain contract; its implementation belongs to the infrastructure layer");
    rule.check(classes);
}
```

#### Why `@Transactional` appears on the interface

The annotation makes each repository method transactional when called on its
own. Inside an active use-case transaction, the default `REQUIRED` behavior joins
that transaction. The use case still coordinates the loan save and event; the
repository can also be called from another transactional component.

#### Other Jakarta Data repository options

The sample uses `CrudRepository`. Jakarta Data also defines `BasicRepository`
for common save, delete, and retrieval operations, and `DataRepository` as a
base for custom repository interfaces. It also supports derived query methods
and pagination types such as `Page`, `PageRequest`, `Order`, and
`CursoredPage`. Add these features when the application needs them. For example,
storing configurable fee tiers would require a persistence model and repository;
the current sample keeps fee tiers in code.

### 3. Domain events with CDI events

#### Events in this example

The model emits two events that cataloging uses:

- **A loan was opened.** Carries: which copy.
- **A loan was closed.** Carries: which copy.

Both carry the copy identifier that cataloging needs to update availability.
Keeping the payload focused limits what subscribers depend on.

A third fact, an overdue fee calculation, is recorded on the loan but isn't
published as an event. If billing or notifications need to react to it, the
design could add an event and a subscriber.

Events let lending publish a fact without calling cataloging directly. In this
sample, CDI observers deliver that fact inside the same application. Moving the
contexts into separate services would require a messaging adapter and decisions
about delivery guarantees and transaction boundaries.

#### Publish and observe CDI events

CDI provides synchronous, type-safe events. This sample uses them directly; it
doesn't use a broker or an outbox.

The events are immutable records:

```java
public record LoanCreated(CopyId copyId) { }
public record LoanClosed(CopyId copyId) { }
```

The publisher injects a typed `Event<T>`:

```java
@UseCase
public class RentBookUseCase {
    private final LoanRepository loanRepository;
    private final CopyAvailabilityValidator copyAvailabilityValidator;
    private final Event<LoanCreated> loanCreatedEvent;
    private final Clock clock;

    public RentBookUseCase() { }

    @Inject
    public RentBookUseCase(LoanRepository loanRepository,
                           CopyAvailabilityValidator copyAvailabilityValidator,
                           Event<LoanCreated> loanCreatedEvent,
                           Clock clock) { … }

    public void execute(CopyId copyId, UserId userId) {
        copyAvailabilityValidator.checkAvailable(copyId);
        var now = LocalDateTime.now(clock);
        loanRepository.save(new Loan(copyId, userId, now));

        loanCreatedEvent.fire(new LoanCreated(copyId));
    }
}
```

The subscriber declares observer methods:

```java
@ApplicationScoped
public class DomainEventListener {

    public void onLoanCreated(@Observes LoanCreated event) { … }
    public void onLoanClosed(@Observes LoanClosed event) { … }
}
```

This approach has several useful properties:

- **Synchronous by default.** The observer runs while the event is fired. In
  this sample, it updates availability within the use-case transaction.
- **Type-safe and compile-checked.** `Event<LoanCreated>` cannot fire a
  `LoanClosed` by accident; a string topic name can.
- **The publisher knows nothing about subscribers.** `RentBookUseCase` has no
  import from `library.catalog`. Add a second listener — audit log, search index,
  metrics — and the lending context does not change.
- **Changing delivery requires design work.** An asynchronous observer or
  message broker changes delivery and transaction behavior; the publisher and
  event contract may also need an adapter.

The `execute` method validates availability, creates and persists a loan, and
publishes the event. The `@UseCase` stereotype supplies the transaction and
logging behavior.

### 4. Domain and application services with CDI

Domain services and application services have different responsibilities.

An **application service** coordinates a use case. In this sample, it starts a
transaction, invokes domain behavior, persists the result, and publishes events.

A **domain service** implements domain behavior that doesn't belong naturally
to one entity or value object. A **port** is different: it's an interface the
domain declares for a capability supplied from outside the model. An adapter
implements that interface.

The sample includes both types of service:

| Class | Kind | Why |
| --- | --- | --- |
| `AddBookToCatalogUseCase`, `RegisterBookCopyUseCase`, `RentBookUseCase`, `ReturnBookUseCase` | Application service | One per journey; pure orchestration |
| `CopyAvailabilityValidator` | **Domain service** | Checks whether another open loan exists for the copy |
| `BookSearchService` | Domain-facing port | Provides the cataloging model with book information from an external source |

`CopyAvailabilityValidator` resides in `library.lending.domain` and uses the
`LoanRepository` port to check the rule. `BookSearchService` resides in
`library.catalog.domain`; its infrastructure adapter calls the external service.

#### 4.1 Application services and the `@UseCase` stereotype

Each use case uses the `@UseCase` CDI stereotype, which combines transaction,
scope, and logging annotations:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Stereotype
@Transactional
@ApplicationScoped
@Logged
public @interface UseCase { }
```

In this application, `@UseCase` gives each use case application scope and a
transaction. The `@Logged` interceptor adds entry, exit, and exception logging.
The interceptor is registered in `beans.xml`:

```xml
<interceptors>
    <class>library.shared.LoggedInterceptor</class>
</interceptors>
```

The stereotype keeps these annotations consistent across use cases and gives the
architecture tests a single annotation to check.

#### 4.2 Domain services and cross-aggregate rules

"At most one open loan per copy" (scenario 3.2) depends on other loans for the
same copy, so `Loan` cannot check it by inspecting only its own state. The
`CopyAvailabilityValidator` domain service uses the repository to check for an
existing open loan.

```java
@ApplicationScoped
public class CopyAvailabilityValidator {
    private LoanRepository loanRepository;

    CopyAvailabilityValidator() { }

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
```

The validator raises `CopyNotAvailableException` when the repository reports
that the copy is unavailable. The exception extends the unchecked
`DomainException` base class.

#### 4.3 Ports and adapters with CDI and the JAX-RS client

Journey J1 needs to resolve an ISBN to a title. The cataloging domain declares
the capability it needs:

```java
// library.catalog.domain — the port
public interface BookSearchService {
    BookInformation search(Isbn isbn);
}
```

The interface doesn't expose HTTP or JSON details. The infrastructure adapter
handles those details:

```java
// library.catalog.infrastructure — the adapter
@ApplicationScoped
public class OpenLibraryBookSearchService implements BookSearchService {

    public BookInformation search(Isbn isbn) {
        var targetUri = UriBuilder.fromUri(baseUrl + "isbn/{isbn}.json").build(isbn.value());
        try (var response = getFollowingRedirects(targetUri)) {
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()) {
                throw new BookNotFoundException(isbn);
            }
            if (response.getStatus() != Response.Status.OK.getStatusCode()) {
                throw new BookSearchException(
                        "failed to search book, upstream returned status " + response.getStatus());
            }
            var result = response.readEntity(OpenLibraryIsbnSearchResult.class);
            return new BookInformation(result.title());
        } catch (ProcessingException e) {
            throw new BookSearchException("failed to search book for isbn: " + isbn.value(), e);
        }
    }
}
```

The adapter translates HTTP failures into domain exceptions: a 404 becomes
`BookNotFoundException`; other unsuccessful responses and connection failures
become `BookSearchException`. `AddBookToCatalogUseCase` handles domain
exceptions rather than HTTP status codes.

The adapter also follows Open Library's redirect to `/books/{key}.json` and
resolves relative `Location` headers against the request URI. These HTTP details
stay in the infrastructure layer.

Because the constructor accepts a base URL, the adapter is testable without a
network — which is what the WireMock tests exploit in Part III.

### 5. Cohesion and decoupling

The sample uses cohesion and explicit dependencies to keep classes focused and
the application easier to test.

**Cohesion — every class has one reason to change.**

| Class | Its single job |
| --- | --- |
| `CopyAvailabilityValidator` | One method: decide whether a copy may be lent |
| `DomainEventListener` | React to other contexts' events |
| `OpenLibraryBookSearchService` | Translate HTTP into domain terms |
| `ClockProducer` | Supply the system clock |
| `RentBookUseCase` | Orchestrate one business transaction |

The use cases coordinate domain operations instead of combining them with HTTP
or persistence-provider details.

**Decoupling — dependencies point inward and are declared, not discovered.**

- **Constructor injection everywhere in main code.** The dependencies of a class
  are its constructor signature. Nothing is hidden in a field annotation, and
  every class is instantiable in a plain unit test. (`@Inject` on fields appears
  only in Arquillian tests, where the container requires it.)
- **Dependency inversion at the port.** `AddBookToCatalogUseCase` depends on the
  `BookSearchService` interface. `OpenLibraryBookSearchService` depends on the
  same interface. Neither knows the other exists; CDI wires them. ArchUnit fails
  the build if `application` ever imports `infrastructure`.
- **Identity references instead of object references** (see Part II §1), so
  aggregates cannot reach into each other.
- **Constructor visibility.** The entity no-arg constructors are package-private
  in this sample; use protected constructors to meet the Jakarta Persistence
  requirement. Use cases inject their dependencies through constructors.
- **Unchecked domain exceptions** mean callers are not forced to catch what they
  cannot handle, and the container still rolls back.

### 6. Packages and ArchUnit rules

The two bounded contexts and the shared technical package from Part I map to
three top-level packages:

```
library
├── shared                    shared technical types — depends on neither context
│   ├── UseCase               stereotype: @ApplicationScoped + @Transactional + @Logged
│   ├── Logged / LoggedInterceptor
│   ├── DomainException       base type for every domain failure
│   └── ClockProducer
├── catalog
│   ├── application           AddBookToCatalogUseCase, RegisterBookCopyUseCase, DomainEventListener
│   ├── domain                Book, Copy, BookRepository, CopyRepository, BookSearchService (port)
│   └── infrastructure        OpenLibraryBookSearchService (adapter)
└── lending
    ├── application           RentBookUseCase, ReturnBookUseCase
    └── domain                Loan, LoanRepository, CopyAvailabilityValidator, OverdueFee, LoanCreated, LoanClosed
```

The lending context has no infrastructure package in this sample. Its repository
interface is implemented by the Jakarta Data runtime; the application still
requires persistence-provider and datasource configuration. The source tree also
doesn't contain a hand-written `BookRepository` adapter.

Packages don't enforce module boundaries by themselves. Eight ArchUnit rules run
as unit tests during `mvn package` and fail the build when a dependency rule is
violated:

| # | Rule | Enforces |
| --- | --- | --- |
| 1 | `..domain..` must not depend on `..application..` or `..infrastructure..` | Layer direction |
| 2 | `..domain..` must not depend on `jakarta.ws.rs..`, `jakarta.enterprise..`, or `jakarta.inject..`, except `CopyAvailabilityValidator` | Domain framework-independence |
| 3 | `..application..` must not depend on `..infrastructure..` | Dependency inversion |
| 4 | `..application..` *must* depend on `..domain..` | Application services orchestrate the model |
| 5 | `library.shared..` must not depend on `catalog..` or `lending..` | Shared package neutrality |
| 6 | `library.lending..` must not depend on `library.catalog..` | Context isolation |
| 7 | `library.catalog..` must not depend on `library.lending..`, except `DomainEventListener` | The one named coupling |
| 8 | Any `*Repository` must reside in `..domain..` | Repository is a port |

Rule 7 documents the event-listener exception:

```java
@Test
void catalogContextMustNotDependOnLendingContextExceptObservedEvents() {
    ArchRule rule = noClasses().that().resideInAnyPackage("library.catalog..")
            .and().doNotBelongToAnyOf(DomainEventListener.class)
            .should().dependOnClassesThat().resideInAnyPackage("library.lending..")
            .because("the only allowed coupling between the contexts is the catalog observing lending "
                    + "domain events (LoanCreated/LoanClosed) via DomainEventListener to update copy availability");
    rule.check(classes);
}
```

The contexts do communicate, so a blanket ban would fail. Whitelisting
`DomainEventListener` makes the dependency visible and reviewable.

#### The trade-off in rule 2

Rule 2 doesn't ban `jakarta.persistence`. `Book`, `Copy`, and `Loan` use
`@Entity`; `Isbn` and `BarCode` use `@Embeddable`. The domain model includes JPA
annotations.

This is a trade-off. A separate persistence model and mapper layer would avoid
JPA annotations in the domain but add types and mapping code. Here, the domain
depends on the JPA API, but not directly on either persistence provider.

The rule also allows one exception: `CopyAvailabilityValidator` uses CDI as a
domain service. The model remains coupled to JPA mapping rules, including
`@AttributeOverride` and the entity constructor visibility requirement.

---

## Part III — Compare requirements with integration tests

The scenario tests in `src/test/java/library/it/LibraryIT.java` exercise use
cases in a container with a database. The coverage matrix shows which Part 0
requirements they verify and which remain untested.

### Coverage matrix

Every Part 0 scenario, in requirement order:

| Req | Scenario | Proved by | Status |
| --- | --- | --- | --- |
| 1.1 | Invalid ISBN refused | `Isbn` record constructor | ⚠️ no dedicated test |
| 1.2 | Duplicate ISBN → `BookAlreadyExistsException` | — | ❌ **not covered** |
| 1.3 | Happy path: ISBN → title → `Book` saved | — | ❌ **not covered** |
| 1.4 | Unknown ISBN → `BookNotFoundException` | `OpenLibraryBookSearchServiceTest` (WireMock) | ✅ |
| 1.5 | Upstream 500 → `BookSearchException` | `OpenLibraryBookSearchServiceTest` | ✅ |
| 1.6 | Network fault → `BookSearchException` | `OpenLibraryBookSearchServiceTest` | ✅ |
| 2.1 | Register a copy → on the shelf, available | — | ❌ **not covered** |
| 2.2 | Missing book or barcode refused | `@NotNull` + `Copy` constructor | ⚠️ no dedicated test |
| 2.R | A copy must belong to a *cataloged* book | — | ❌ **rule not implemented** |
| 3.1 | Rent an available copy → loan opens, copy leaves the shelf | `LibraryIT.testLibraryCrud` | ✅ |
| 3.2 | Rent an already-loaned copy → refused | `LibraryIT.testLibraryCrud` | ⚠️ assertion too loose |
| 3.3 | Two borrowers, one copy, one winner | — | ❌ **not covered** |
| 4.1 | Return → loan closes, copy back on the shelf | `LibraryIT.testLibraryCrud` | ✅ |
| 4.2 | Unknown loan → `LoanNotFoundException` | — | ❌ **not covered** |
| 4.3 | On-time return → no fee | `LibraryIT.testLibraryCrud` (implicit) | ✅ |
| 4.4 / 5 | Late return → `BEYOND_A_MONTH` fee | `LibraryIT.testOverdueReturn` | ✅ |
| 5 | All five fee tiers | `OverdueFeeTest` (unit) | ✅ |
| II §6 | All 8 architecture rules | `ArchitectureTest` | ✅ |

The matrix shows gaps in the catalog and lending scenarios. The lending tests
cover more of the fee and availability behavior, but the concurrent-rental
scenario still needs a test.

#### Gaps in the current tests and implementation

**The tests don't exercise the J1 and J2 use cases.** `LibraryIT` creates its
fixtures by constructing aggregates directly:

```java
Book book = new Book("Effective Java", new Isbn("9780134685991"));
bookRepository.save(book);

Copy copy1 = new Copy(copyId, book.getId(), new BarCode("BC001"));
copyRepository.save(copy1);
```

This is reasonable fixture setup, but it doesn't test the full sequence in J1:
duplicate checking, bibliographic lookup, and saving a book. It also doesn't
exercise J2 through its use case. WireMock tests cover the search adapter's error
translation, but not the use case that coordinates it. Add use-case tests with a
test implementation of `BookSearchService` and repositories in a container.

**Requirement 2.R is stated but not implemented.** `RegisterBookCopyUseCase` does
this and nothing more:

```java
public void execute(@NotNull BookId bookId, @NotNull BarCode barCode) {
    copyRepository.save(new Copy(bookId, barCode));
}
```

There is no check that `bookId` names a book in the catalog. Registering a copy
against a book that does not exist succeeds, and because aggregates reference each
other by identity with no foreign key (see Part II §1), nothing else will catch it
either. This is a genuine gap between the stated requirement and the code — either
the rule should be enforced with a `bookRepository.existsById(bookId)` check, or
the domain expert should confirm that a copy may legitimately precede its
catalog entry. It is a conversation, not a silent fix.

**Requirement 3.3 has no test.** `Copy` has an `@Version` field, but the sample
doesn't test two concurrent rentals. A concurrency test is needed to verify that
the implementation allows only one loan to commit.

**Scenario 3.2's assertion is too loose:**

```java
assertThrows(Exception.class, () -> rentBookUseCase.execute(…));
```

`Exception.class` is too broad. The test would still pass if renting failed
with a `NullPointerException`, a persistence error, or a validation failure —
none of which prove the copy was rejected *for being unavailable*. It should be
`assertThrows(CopyNotAvailableException.class, …)`.

### How the scenario test is built

**Deployment.** ShrinkWrap assembles a WAR, resolving test libraries straight off
the Maven classpath:

```java
@Deployment
public static WebArchive createDeployment() {
    File[] extraJars = Maven.resolver().loadPomFromFile("pom.xml")
            .importCompileAndRuntimeDependencies()
            .resolve("org.assertj:assertj-core", "org.awaitility:awaitility")
            .withTransitivity().asFile();
    return ShrinkWrap.create(WebArchive.class, "LibraryIT.war")
            .addAsLibraries(extraJars)
            .addPackages(true, "library.shared", "library.lending", "library.catalog")
            .addAsResource("test-persistence.xml", "/META-INF/persistence.xml")
            .addAsWebInfResource("test-beans.xml", "beans.xml");
}
```

`addPackages(true, …)` pulls in all three modules; `test-persistence.xml` is
swapped in for the production one (same `defaultPU`, `drop-and-create` schema
generation, SQL logging on for both providers). Arquillian writes the assembled
WAR to `target/` via `deploymentExportPath`, so you can unzip and inspect exactly
what was deployed when something fails.

**Injection.** The Jakarta Data repositories arrive as ordinary CDI beans — no
test doubles, no in-memory fakes:

```java
@PersistenceContext private EntityManager em;
@Inject private UserTransaction ux;
@Inject private BookRepository bookRepository;
@Inject private CopyRepository copyRepository;
@Inject private LoanRepository loanRepository;
@Inject private RentBookUseCase rentBookUseCase;
@Inject private ReturnBookUseCase returnBookUseCase;
```

**Transactions.** There is no HTTP request to carry a container-managed
transaction, so the test demarcates explicitly with a small helper:

```java
private void startTx() throws Exception { ux.begin(); em.joinTransaction(); }

private void withTx(Runnable action) throws Exception {
    try { startTx(); action.run(); endTx(); }
    catch (Exception e) { ux.rollback(); }
}
```

Each `withTx` block runs inside a user-managed transaction. The helper catches
and logs `Exception` but doesn't rethrow it, so an unexpected exception can be
hidden if no later assertion detects the failed operation. `AssertionError`
isn't caught, so assertion failures still fail the test.

**Availability assertions.** The test polls for the copy's updated state:

```java
await().atMost(5000, TimeUnit.MILLISECONDS).untilAsserted(() -> {
    var copyOptional = copyRepository.findById(copyId);
    assertThat(copyOptional).isPresent();
    assertThat(copyOptional.get().isAvailable()).isFalse();
});
```

The event observer is synchronous and runs as part of the use-case transaction,
so polling doesn't demonstrate eventual consistency. A direct assertion after
the operation would better describe this implementation.

**The context boundary is visible.** Renting converts between the two `CopyId`
types:

```java
rentBookUseCase.execute(library.lending.domain.CopyId.of(copyId.id()), userId);
```

The conversion makes the context-specific identifiers explicit.

**The overdue scenario** constructs a loan whose due date is 35 days in the past
via `Loan`'s public four-argument constructor, then returns it:

```java
var pastDate = LocalDate.now().minusDays(35);
var loan = new Loan(
        library.lending.domain.CopyId.of(copyId.id()),
        userId,
        LocalDateTime.now().minusDays(35),
        pastDate);
loanRepository.save(loan);

returnBookUseCase.execute(loan.id());
// ...
assertThat(loan.overdueFee()).isEqualTo(OverdueFee.BEYOND_A_MONTH.amount());
```

This is the value of injecting a `Clock` and exposing a timestamp-taking
constructor: the test controls time instead of sleeping for a month.

**Adapter tests** use WireMock to exercise the JAX-RS client against a stubbed
Open Library service on `localhost:8080`. They cover the 302-to-200 redirect,
404, 500, and `Fault.CONNECTION_RESET_BY_PEER` without calling the real service.

---

## Part IV — Run the code

### Prerequisites

Install the following tools before you build:

- **Java 21** (the POM sets `maven.compiler.release=21`)
- **Maven 3.9.x**
- **Git**
- Any editor — IntelliJ IDEA Community, or VS Code with the Java extension pack

Docker is not required. The container profiles download and unpack an app server
into `target/` for you.

### Clone and build

Run these commands from a terminal:

```bash
git clone https://github.com/hantsy/jakartaee-ddd-example
cd jakartaee-ddd-example
mvn clean package
```

`mvn clean package` compiles, runs the **unit and architecture tests**, and
produces `target/implementing-ddd-with-jakartaee.war`. By default
`skip.unit.tests=false` and `skip.integration.tests=true`, so no app server is
started. This is the fast feedback loop and it includes all eight ArchUnit rules,
`OverdueFeeTest`, and the WireMock adapter tests.

The default build runs the EclipseLink JPA metamodel processor, so static
metamodel classes (`Book_`, `Copy_`, `Loan_`, …) are generated alongside your
entities — but **no repository implementations**. On GlassFish/EclipseLink the
repository is realized reflectively by the runtime at deployment. Building with
the WildFly profile swaps in `org.hibernate.orm:hibernate-processor`, which
instead emits a compiled `BookRepository_` / `CopyRepository_` / `LoanRepository_`
implementation at compile time:

```bash
mvn -B -P wildfly compiler:compile
# then inspect target/generated-sources/annotations/library/catalog/domain/
```

See Part II §2 for the runtime-versus-compile-time comparison.

### Run the scenario tests on GlassFish 8

Use this command to run the scenarios in a managed GlassFish server:

```bash
mvn clean verify -Parq-glassfish-managed
```

What happens: `maven-dependency-plugin` unpacks GlassFish 8.0.5 into `target/`,
failsafe sets `GLASSFISH_HOME=target/glassfish8`, Arquillian starts a managed
instance (admin port 4848, Derby enabled), deploys `LibraryIT.war`, and runs the
scenarios against a Derby database. The profile flips `skip.unit.tests=true` and
`skip.integration.tests=false`.

The default `glassfish` profile is also active and carries a Cargo configuration
(a Derby datasource bound to `jdbc/__default`, `cargo.glassfish.removeDefaultDatasource=true`),
with downloads cached in `../installs`. That is for Cargo-driven local
deployment; the Arquillian profile manages its own container.

### Run the scenario tests on WildFly Preview

Use this command to run the scenarios in a managed WildFly server:

```bash
mvn clean verify -Parq-wildfly-managed
```

Same shape, different stack: WildFly Preview 41.0.1.Final (`wildfly-preview-dist`)
unpacked into `target/`, `JBOSS_HOME` set, `standalone-full.xml`, and Hibernate
7.4 supplied as a `provided` dependency with the Hibernate annotation processor
swapped in for EclipseLink's. **The `wildfly-preview` distribution is required** —
regular WildFly does not yet ship the Jakarta EE 11 APIs this project compiles
against.

Running the identical WAR against two providers is the real proof that the domain
model is not coupled to either. It is also how you find provider-specific rough
edges early.

### Continuous integration

Three GitHub Actions workflows, all on Java 21 / Temurin with a Maven cache:

| Workflow | Command |
| --- | --- |
| `build.yml` | `mvn -B package` |
| `arq-glassfish-managed.yml` | `mvn -B -q clean verify -Parq-glassfish-managed` |
| `arq-wildfly-managed.yml` | `mvn -B -q clean verify -Parq-wildfly-managed` |

The GlassFish job uploads `target/glassfish*/glassfish/domains/domain1/logs/server.log`
as an artifact on failure, which is usually the only way to see what the container
actually complained about.

### Known issues

The README carries both of these; neither is a design problem.

1. **EclipseLink on GlassFish.** An EclipseLink defect broke tests on GlassFish
   8.0.0 — tracked as
   [eclipselink#2656](https://github.com/eclipse-ee4j/eclipselink/issues/2656).
   The project pins GlassFish 8.0.5 and EclipseLink 5.0.2.
2. **`BookSearchServiceIT` is `@Disabled`.** The in-container version of the
   search test hits the real `openlibrary.org`, and a default GlassFish domain
   sets `javax.net.ssl.trustStore` JVM-wide to its own two-entry
   `config/cacerts.p12` — no CA roots — so PKIX validation fails. The reason is
   recorded on the annotation itself, with the upstream issue
   ([glassfish#26258](https://github.com/eclipse-ee4j/glassfish/issues/26258)):

   > GlassFish sets `-Djavax.net.ssl.trustStore` JVM-wide to the domain's
   > `config/cacerts.p12`, which holds only the s1as/glassfish-instance
   > self-signed certs and no CA roots, so the in-container JAX-RS client fails
   > PKIX validation against `openlibrary.org`. Passes on WildFly.

   The equivalent WireMock unit tests cover the same adapter logic without the
   network, so coverage is not lost — only the true end-to-end path is.

### No HTTP API is included

`src/main` contains no `@Path` or `@ApplicationPath` class, so the WAR doesn't
expose an HTTP endpoint. Run the integration tests or inject the use cases into
another component to exercise the application.

To add a REST layer, put the resource in an outer adapter package, inject the
relevant use case, and map `DomainException` subtypes to HTTP statuses with an
`ExceptionMapper`. Keep Jakarta REST dependencies out of the domain package;
ArchUnit rule 2 checks that boundary.

---

## Takeaways

### Strategic design

- **Classify subdomains for your organization.** In this example, lending is
  core, cataloging is supporting, and bibliographic lookup is generic.
- **A subdomain is not a bounded context.** One partitions the business, the other
  partitions the model. They often coincide — and here the generic subdomain
  deliberately does *not* get a context of its own.
- **Use model boundaries that reflect differences in language and change.** A
  fee-tier change affects lending; a title change affects cataloging.
- **The same word meaning two different things is the boundary signal.** "Copy" is
  a physical asset in cataloging and a borrowed identifier in lending. Do not
  glossary that away; build the boundary there.
- **Make context relationships explicit.** This sample shares identifiers and
  events in one application; separate deployments need additional integration
  design.
- **Pin the ubiquitous language down and hunt its traps.** "Available" needed an
  exact definition; "return" turned out to be both an act and a date, and only the
  date determines the fee.
- **Keep cross-context dependencies visible.** `DomainEventListener` is the
  cataloging class that observes lending events.

### Tactical design

- **Distinguish entities from values.** This sample has three entity types;
  identifiers and event payloads are represented as value objects.
- **Identifiers are value objects.** Wrapping them — and giving lending a `CopyId`
  that cannot mint its own UUID — puts ownership in the type system.
- **Use identifiers across aggregate boundaries.** The sample avoids JPA
  associations between its aggregates.
- **A rule that must consult more than one instance is a domain service**, not an
  entity method. One loan cannot see the other loans against the same copy, so
  "at most one open loan" lives in `CopyAvailabilityValidator`.
- **Keep application services focused on orchestration.** The use cases validate,
  construct, persist, and publish events.
- **Domain events carry the minimum a subscriber needs.** One `CopyId`, not a
  whole `Loan`.
- **Declare repository contracts with Jakarta Data.** The sample keeps the
  interfaces in the domain package and uses runtime-provided implementations.
- **Jakarta Data is a spec, not a library.** The interface is yours; the
  implementation is your runtime's. EclipseLink proxies it reflectively at
  deployment; Hibernate compiles a `BookRepository_` via its annotation
  processor. Reference only the interface, and build for the server you deploy to.
- `@Query` returning `boolean`, plus a `default findByIdOrThrow` throwing a
  `DomainException`, are enough to make a generated repository feel like domain
  code rather than a DAO.
- **CDI stereotypes** (`@UseCase`) collapse scope + transaction + logging into one
  greppable, enforceable concept.
- **Use CDI events for in-process communication.** A broker or separate service
  requires additional delivery and transaction design.
- **Use constructor injection** to make dependencies visible. Use public or
  protected no-arg constructors for JPA entities, as required by the
  specification.
- **ArchUnit** turns "we agreed on this architecture" into "the build fails if you
  break it" — eight rules, including one that whitelists a single class so the
  exception stays visible.
- **JPA annotations in the domain are a compromise, not a solution.** Know which
  one you are making, write it down, and ban the annotations that would make it
  worse.

### Verification

- **Write the requirements down before reading the code.** Doing so surfaced a
  stated rule nobody had implemented (a copy must belong to a cataloged book)
  and an untested one (two borrowers racing for one copy) — neither visible from
  the code alone.
- **Map every scenario ID to a test.** The coverage matrix is the deliverable; the
  ❌ rows are the backlog.
- **Run the application against each supported provider in CI.** This checks
  compatibility with those providers, but doesn't prove portability in general.
- **Inject the real Jakarta Data repositories** into scenario tests rather than
  faking them — the point is that the generated implementation works.
- **Assert the specific domain exception.** `assertThrows(Exception.class, …)`
  proves almost nothing.
- **Record a disabled test's reason on the annotation**, with the upstream issue
  link.

Source code: [hantsy/jakartaee-ddd-example](https://github.com/hantsy/jakartaee-ddd-example).

### Further reading

- [Domain-Driven Design](https://martinfowler.com/bliki/DomainDrivenDesign.html)
  by Martin Fowler provides an overview of DDD concepts.
- [An Introduction to Domain-Driven Design](https://www.dddcommunity.org/learning-ddd/haywood_2010/)
  by Dan Haywood is an introductory article listed by the DDD Community.
- [DDD Reference](https://www.domainlanguage.com/ddd/reference/) by Eric Evans
  is a concise pattern reference for readers who already know the basics.
