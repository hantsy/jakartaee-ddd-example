---
title: "Applying DDD with Jakarta EE — Strategic and Tactical Design with Jakarta Data"
date: 2026-10-08
tags: [jakarta-ee, jakarta-data, ddd, event-storming, strategic-design, tactical-design, jpa, cdi, archunit]
repo: https://github.com/hantsy/jakartaee-ddd-example
status: draft
---

# Applying DDD with Jakarta EE: Strategic and Tactical Design with Jakarta Data

Domain-Driven Design splits into two halves, and most Jakarta EE writing only
ever covers the second one.

**Strategic design** is a conversation with the domain expert. Which parts of the
business are worth investing in and which are not. What words mean. Where the
model boundaries go and how the pieces on either side relate. It produces
subdomains, a ubiquitous language, bounded contexts, and a context map — and not
one line of code.

**Tactical design** is what you build afterwards: entities, value objects,
aggregate roots, repositories, domain events, domain services, application
services. This is where Jakarta EE 11 lines up with Evans' building blocks almost
one-to-one — and where **Jakarta Data** finally turns the repository from
something you write into something you declare.

The two halves have different audiences, so this post keeps them strictly apart.
Parts 0 and I contain no framework names and no code. Part II is where every
strategic decision becomes Jakarta EE.

The subject is [`jakartaee-ddd-example`](https://github.com/hantsy/jakartaee-ddd-example),
a library system in two bounded contexts — a reimplementation of Maciej
Walkowiak's
[Spring IO 2024 talk *Implementing Domain Driven Design with Spring*](https://www.youtube.com/watch?v=VGhg6Tfxb60)
([original sample](https://github.com/maciejwalkowiak/implementing-ddd-with-spring-talk)),
ported to Jakarta EE 11.

---

# Part 0 — Requirements

Rather than a feature list, the requirements are captured two ways: a **story**
that reads the way a librarian would tell it, and an **event storm** that pins
down the commands, events, policies, and actors precisely. Then each journey gets
numbered acceptance scenarios, which Part III checks against the code one by one.

## 0.1 The story

> It is Monday morning and the library has just received a new shipment. Priya,
> the cataloguing librarian, picks up *Effective Java* and types its ISBN into
> the desk terminal. She does not type the title — the system looks it up in the
> national bibliographic service and fills it in. If the number is wrong, or the
> book is already on the catalogue, the system tells her and changes nothing. She
> then sticks four barcodes on four physical copies and registers each one. All
> four go on the shelf as available.
>
> At lunchtime Sam borrows one of the four. The desk scans the barcode, and a
> loan opens for thirty days. That copy immediately leaves the shelf — if Priya
> looks at the catalogue she will see three available, not four. If Sam's friend
> tries to borrow the same physical copy a minute later, the desk refuses: that
> copy is out. The other three are unaffected.
>
> Six weeks later Sam brings it back. The desk closes the loan and the copy goes
> back on the shelf. Because it is more than thirty days late, a fee is charged —
> and the amount depends on how late it is. A week late costs less than a month
> late. The fee belongs to that loan, not to Sam as a person and not to the copy:
> the same copy returned on time by another borrower costs nothing.
>
> Meanwhile the other three copies have been borrowed and returned a dozen times
> each. The catalogue entry for *Effective Java* has not changed once.

Three things in that story are load-bearing, and the rest of the design falls out
of them:

- Priya never retypes a title, and a failed lookup leaves no half-created entry.
- The shelf and the loans are two different concerns that must nonetheless agree.
- A fee is a property of one loan, decided once, at the moment of return.

## 0.2 The event storm

Standard Alberto Brandolini notation, flattened to text:

```
  (actor)          person who does something
  [blue]  COMMAND  imperative — someone wants something to happen
  [orange] EVENT   past tense — it happened, it cannot be undone
  [violet] POLICY  "whenever <event>, then <command>"
  [green]  READ    information a person looks at to decide what to do
  [pink]   EXTERNAL a system outside our control
  «AGGREGATE»      the thing that decides whether a command is allowed
```

**Cataloguing journey**

```
(Librarian) ─▶ [Catalogue Book] ─▶ «BOOK» ─▶ [Book Catalogued] ─▶ (Catalogue listing)
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

### The storm, tabulated

**Domain events** — what actually happened, in the past tense.

| Event | Raised by | Who cares |
|---|---|---|
| Book Catalogued | the book | cataloguing staff, the catalogue listing |
| Copy Registered | the copy | the shelf |
| Loan Opened | the loan | the borrower, and the shelf |
| Loan Closed | the loan | the desk, the shelf, and billing |
| Copy Off Shelf / Copy On Shelf | the copy | anyone looking at availability |
| Overdue Fee Charged | the loan | billing — *identified, not yet emitted* |

**Commands** — what people ask for.

| Command | Actor | Aggregate that decides |
|---|---|---|
| Catalogue Book | Librarian | Book |
| Register Copy | Librarian | Copy |
| Lend Copy | Borrower | Loan |
| Return Copy | Desk staff | Loan |

**Policies** — the automatic reactions. These are where the two halves of the
library meet.

| Whenever | Then |
|---|---|
| A book is catalogued and its ISBN is already present | Refuse; change nothing |
| A title cannot be resolved | Refuse; leave the catalogue untouched |
| A loan opens | Take that copy off the shelf |
| A loan closes | Put that copy back on the shelf |
| A loan closes after its due date | Charge the matching tier |

**Read models** — Catalogue listing, Shelf status, Borrower's loans. Each is fed
by events, and none of them is allowed to *decide* anything.

**External system** — the Bibliographic Service. It is the only thing in the
storm the library does not control, which is exactly why it appears once, behind
a command of its own.

## 0.3 Journeys and acceptance scenarios

The numbered IDs below are what Part III's coverage matrix refers back to.

### J1 — Catalogue a book by ISBN

| # | Scenario | What must happen |
|---|---|---|
| 1.1 | The number typed is not a valid ISBN | Refused on the spot. Nothing is added; the librarian is told the number is wrong. |
| 1.2 | The book is already in the catalogue | Refused as a duplicate. The existing entry is untouched — no second record, no overwrite. |
| 1.3 | The ISBN is valid and new | The title is resolved from the bibliographic service and a new catalogue entry is created. |
| 1.4 | The bibliographic service has never heard of the ISBN | The librarian is told the book could not be found. Nothing is added — an entry with no title is worse than no entry. |
| 1.5 | The bibliographic service is failing | The librarian is told the lookup failed and may retry later. Nothing is added. |
| 1.6 | The network is down | As 1.5. |

**Business rule.** Cataloguing a book is all-or-nothing. If the title cannot be
resolved, no partial entry is left behind for someone to clean up later.

### J2 — Register a physical copy

| # | Scenario | What must happen |
|---|---|---|
| 2.1 | A valid book and barcode | A new copy exists, on the shelf, available to borrow. |
| 2.2 | Either the book or the barcode is missing | Refused. A copy must belong to a book and must be identifiable at the desk. |

**Business rule.** A copy can only be registered against a book that is already
in the catalogue. *Whether the system enforces this is a separate question — see
Part III.*

### J3 — Lend a copy

| # | Scenario | What must happen |
|---|---|---|
| 3.1 | The copy is on the shelf | A loan opens, due in thirty days. The copy leaves the shelf. |
| 3.2 | The copy is already out | Refused. The borrower is told the copy is unavailable. No second loan opens. |
| 3.3 | Two borrowers ask for the same copy at the same instant | Exactly one loan opens; the other is refused. |

**Business rules.** A copy is on at most one open loan at a time. The due date is
always thirty days out. Lending is atomic — either the loan exists *and* the copy
is off the shelf, or neither is true.

### J4 — Return a copy

| # | Scenario | What must happen |
|---|---|---|
| 4.1 | The loan is open | The loan closes, the return date is recorded, the copy is back on the shelf. |
| 4.2 | The loan is not recognised | Refused. The desk cannot close a loan it cannot find. |
| 4.3 | Returned on or before the due date | No fee. |
| 4.4 | Returned after the due date | A fee is charged, per J5. |

### J5 — The overdue fee

| Days late | Fee |
|---|---|
| Not late | — |
| 1 – 7 | 5.00 |
| 8 – 14 | 10.00 |
| 15 – 30 | 20.00 |
| More than 30 | 50.00 |

**Business rules.** The fee is a flat tier amount, not a per-day accrual. It is
decided once, at the moment of return. It belongs to the loan — not to the copy,
and not to the borrower.

---

# Part I — Strategic Design

Strategic design is what the domain expert and the architect settle *together*,
before anyone opens an editor. It answers the questions you cannot fix later with
a refactor. Nothing in this section mentions a framework, a class, or an
annotation.

## 1. Subdomain classification

Not every part of a business deserves the same investment. DDD sorts the problem
space into three kinds of subdomain, and the sort drives where your best people
and your most careful modelling go.

- **Core** — the reason the organisation exists. If this is mediocre, the
  business is mediocre. Nobody sells you this; you build it.
- **Supporting** — necessary, but not differentiating. It has to work and it has
  to be correct, yet nobody chooses your library because of it.
- **Generic** — a solved problem that every business in the sector has. You buy
  it, rent it, or integrate with a standard service. You never build it.

For this library:

| Subdomain | Class | Why | Consequence |
|---|---|---|---|
| **Lending** | **Core** | This is what a library *does*. Loan length, availability contention, the tiered fee — these are policy decisions that differ from library to library and change with the board's mood. There is no product to buy that encodes *this* library's rules. | Deserves the careful modelling, the invariants, the concurrency handling, and the tests. |
| **Cataloguing** | **Supporting** | Holding a list of works and physical items is bookkeeping. It must be accurate, and it follows external standards (ISBN, bibliographic records), but no borrower chooses a library over its catalogue schema. | Build the simplest thing that is correct. Resist gold-plating. |
| **Bibliographic lookup** | **Generic** | Resolving an ISBN to a title is a completely solved problem, offered by Open Library, WorldCat, and national libraries. Writing your own would be a waste and a liability. | Never implement. Integrate — and keep the integration replaceable, because the provider will change. |

**What the classification actually buys you.** It tells you where to spend. The
core subdomain is the one place in this system with real contention, real money,
and real invariants — and correspondingly it is where the model is richest. The
generic subdomain is the one place with an adapter behind a boundary, precisely
so that swapping Open Library for WorldCat next year touches one class.

Note also what is *absent* from the list: there is no "user management"
subdomain. A borrower is only ever an identifier on a loan. The moment someone
proposes building authentication, profiles, and notifications in here, the
subdomain map is the artefact that says *that is generic, buy it, and keep it out
of the core*.

**One caution.** A subdomain is a partition of the *business*. A bounded context
is a partition of the *model*. They often coincide, and confusing them is a
common mistake — the business does not reorganise just because you redrew a
package diagram. Here they deliberately align, with one exception: the generic
bibliographic subdomain does not get its own context. It lives inside
cataloguing, behind a boundary that cataloguing owns.

## 2. Ubiquitous language

One vocabulary, agreed up front, used identically in conversation, in the
requirements, and eventually in the code:

| Term | What it means here |
|---|---|
| **Book** | A *work*. A catalogue entry, identified by its ISBN. Not a physical object. |
| **Title** | The published name of a book. |
| **ISBN** | The international standard number identifying a book. |
| **Copy** | One physical item on the shelf — a specific book, with its own barcode. |
| **Barcode** | The sticker on a copy, scanned at the desk. |
| **Shelf status** | Whether a copy is currently available to borrow. |
| **Borrower** | A library user permitted to take a copy out. |
| **Loan** | The record that a borrower holds a copy — opened on one date, due on another. |
| **Due date** | Thirty days after a loan opens. |
| **Return** | Closing an open loan. |
| **Overdue** | A copy that came back after its due date. |
| **Overdue fee** | A tiered charge for lateness. |
| **Catalogue** | The library's list of the books it holds. |

The distinction that carries the whole model: **a book is not a copy.** The
library holds one *book* called *Domain-Driven Design* and four *copies* of it.
Collapsing those two is the classic error in library systems — it is what forces
you to duplicate the title four times, and what makes it impossible to say "this
one is overdue but that one is on the shelf."

Two language traps the glossary exists to prevent:

- **"Available" is ambiguous.** Available to *whom*, decided *by what*? In this
  domain it means precisely: no open loan exists against this copy. Anything
  vaguer and the cataloguing staff and the desk staff will disagree silently.
- **"Return" is a verb and a date.** The act of closing a loan, and the recorded
  day it happened, are different things — and the fee depends only on the second.

A ubiquitous language is only real if it survives contact with the codebase.
Part II shows the classes named after these exact terms — no DTO suffixes, no
numbered variants, no helper classes, and no second word for the same thing.

## 3. Bounded contexts

Two groups of people, two sets of concerns, two vocabularies.

**Cataloguing** belongs to the staff who maintain the library's holdings. They
care about what the library *owns*: which works are held, what they are called,
how many physical items exist, and whether each item is on the shelf. Their world
changes slowly — a title is corrected once a year, a barcode is printed and stuck
on once and then never edited.

**Lending** belongs to the desk staff. They care about *who has what until when*.
Their world changes hourly, is full of contention and deadlines, and is the only
place in the library where money moves.

These are separate bounded contexts for three reasons:

1. **The word "copy" means different things to them.** To cataloguing, a copy is
   a physical asset with a barcode and a shelf status. To lending, a copy is
   nothing but an identifier on a loan — lending never scans a barcode and never
   decides what is on the shelf. Same word, two models. That is the textbook
   signal for a context boundary.
2. **They change for different reasons.** A new fee tier is a lending change. A
   corrected title is a cataloguing change. Neither should be able to break the
   other, and neither should need the other's release window.
3. **They would be owned by different teams** in any real library system, and
   would eventually want different storage and different scaling behaviour.

A third area — the **shared kernel** — holds only what both sides must agree on:
the notion of a use case, the notion of a domain failure, and the library's clock.
It contains no library-domain knowledge whatsoever. If it ever does, one of the
two contexts has leaked into it, and the kernel has started its slow slide into
becoming a third context that nobody owns.

## 4. Context map

Two relationships, running in opposite directions. Getting the direction right is
the whole game; getting it wrong produces a ball of mud that nobody can split
later.

**Lending depends on cataloguing for identity.**

Lending must be able to say *which* copy is on loan, so it borrows cataloguing's
notion of a copy identifier. But it borrows it strictly as a *consumer*: lending
never invents a copy identifier and never changes one. In DDD terms, lending is
downstream and conforms to the identity cataloguing publishes.

Just as important is what lending refuses to adopt. It has no idea what a barcode
is, no idea whether a copy is on the shelf, and no ability to change either. It
knows only that an identifier names a copy. That refusal is what keeps lending
small, and it is why a change to how barcodes are printed cannot break a loan.

**Cataloguing depends on lending for facts.**

Cataloguing must keep the shelf status honest, and the only authority on whether
a copy is out is lending. So cataloguing *listens*. When lending announces that a
loan opened, the copy leaves the shelf; when it announces that a loan closed, the
copy returns. Cataloguing never asks lending a question and never reads lending's
records.

**So the arrows cross.** Cataloguing is downstream of lending for facts, and
lending is downstream of cataloguing for identity. Neither reaches into the
other's data; the only thing that crosses the boundary is an announcement. That
is a healthy map — a bidirectional *data* dependency between two contexts is the
one you can never untangle, whereas two one-way event and identity flows can each
be replaced independently.

**One named meeting point.** There is exactly one place in cataloguing that ever
looks at a lending concept, and it is the place that reacts to lending's
announcements. Everything else in cataloguing has never heard of lending. Part II
shows how that is enforced mechanically, rather than left to goodwill and code
review.

**A consequence worth stating.** Because shelf status is derived from lending's
announcements rather than written by lending, it is a *read model*: eventually
consistent, and permitted to lag by the time an announcement takes to arrive. The
authority on whether a copy may be lent stays with lending. Two contexts, one
fact, one owner.

## 5. What the strategy commits us to

Six decisions, taken before a single line of code:

1. **Three subdomains.** Lending is core and gets the modelling effort.
   Cataloguing is supporting and stays simple. Bibliographic lookup is generic
   and is never implemented, only integrated.
2. **Two bounded contexts and one shared kernel.** Cataloguing and lending share
   no data — only identifiers and announcements.
3. **One ubiquitous language per context.** "Copy" deliberately means two
   different things, and nothing translates between them except the named
   meeting point.
4. **A context map that runs both ways.** Lending conforms to cataloguing's
   identities; cataloguing subscribes to lending's facts. Exactly one class in
   cataloguing knows lending exists.
5. **Shelf status is cataloguing's read model** of lending's decisions, and is
   allowed to lag.
6. **The generic subdomain sits behind a boundary cataloguing owns**, so the
   provider can be swapped or bought without touching the model.

Part I stops here. Which concepts are entities, which are values, where the
aggregate boundaries fall, how things are stored and retrieved, and what crosses
the boundary as an event — those are tactical questions, and Part II answers them
in code.

---

# Part II — Tactical Design: implementing it with Jakarta EE

Tactical design takes the strategic decisions and turns them into classes. The
building blocks are Evans': **entities**, **value objects**, **aggregate roots**,
**repositories**, **domain events**, **domain services**, and **application
services**. The headline is how directly Jakarta EE 11 supplies each one — and
Jakarta Data, new in this release, is the piece that removes the most code.

## From decision to code

Every commitment taken in Part I §5 lands somewhere specific. This is the map for
the rest of the part:

| Strategic commitment | Where it lands | Mechanism |
|---|---|---|
| 1. Generic subdomain stays replaceable | `BookSearchService` → `OpenLibraryBookSearchService` | Domain port + CDI adapter; swapping providers touches one class |
| 2. Two contexts, no shared data | `library.catalog..` / `library.lending..` | Packages as modules + ArchUnit rules 6 & 7 |
| 3. One ubiquitous language per context | `catalog.domain.CopyId` vs `lending.domain.CopyId` | Two types, same shape; lending's cannot mint its own |
| 4. Exactly one named meeting point | `DomainEventListener` | The single class whitelisted by ArchUnit rule 7 |
| 5. Shelf status is a read model | `Copy.available` | `@Observes` on a CDI `Event<T>` |
| 6. The generic sits behind a boundary | `library.catalog.infrastructure` | ArchUnit rule 3: application never imports infrastructure |

And the tactical building blocks themselves:

| Building block | In this sample | Jakarta EE mechanism |
|---|---|---|
| Entity / aggregate root | `Book`, `Copy`, `Loan` | JPA `@Entity`, `@EmbeddedId`, `@Version` |
| Value object | `Isbn`, `BarCode`, every `*Id` | Java `record` + `@Embeddable` |
| Repository | `BookRepository`, `CopyRepository`, `LoanRepository` | **Jakarta Data** `@Repository` + `CrudRepository` |
| Domain event | `LoanCreated`, `LoanClosed` | **CDI** `Event<T>` / `@Observes` |
| Domain service | `CopyAvailabilityValidator`, `BookSearchService` | CDI bean / plain domain interface |
| Application service | the four `*UseCase` classes | `@UseCase` stereotype |
| Transaction & rollback | every use case | JTA `@Transactional` + unchecked `DomainException` |
| Adapter | `OpenLibraryBookSearchService` | CDI `@ApplicationScoped` + JAX-RS `Client` |
| Cross-cutting policy | logging | CDI `@Interceptor` + `@InterceptorBinding` |
| Time as a dependency | loan dates | CDI `@Produces` `Clock` |
| Persistence config | `defaultPU` → `java:comp/DefaultDataSource` | `persistence.xml`, JTA datasource |
| Bean discovery | interceptor registration | `beans.xml` 4.0 |

The building blocks in the second table each get a section below.

## 1. Entities, value objects and aggregate roots ↔ JPA

### Which concepts are entities, and which are values

The test is one sentence: **if two things have exactly the same attributes, are
they the same thing?** If yes, it is a value. If no — if we track it individually
through time — it is an entity.

**Entities.** Three of them, matching the three aggregates the event storm
identified.

- **Book** — the catalogue entry for a work. We correct its title, we keep it for
  decades, and we mean *that entry* even as its attributes change. Two books that
  happen to share a title are not the same book.
- **Copy** — the clearest entity in the system, and the reason the model works at
  all. The library may hold four copies of the same book, with identical titles
  and identical ISBNs. They are four different things: one can be on the shelf
  while another is three weeks overdue. If a copy were a value, the library could
  not lend.
- **Loan** — a fact with a life of its own. It opens, it stays open, it closes,
  and it may accrue a fee. Two loans of the same copy by the same borrower a year
  apart are unambiguously two loans — and we need both, because both charged a
  fee.

**Value objects.** Everything else.

- **ISBN** and **BarCode** — two identical ISBNs are the same ISBN. You never
  update one; you replace it. Losing the barcode label does not lose the item.
- **Every identifier** — of a book, a copy, a borrower, a loan. Treating an
  identifier as a thing-in-itself is precisely how "the id" escapes the model and
  ends up printed on every screen, report, and API, where it can never be changed
  again.
- **A bibliographic lookup result** — a snapshot, true at the instant it was
  fetched, used once, discarded. If the publisher corrects the title tomorrow, our
  snapshot does not change, and should not.
- **A fee tier** — "one to seven days late costs five" is a rule, not a thing.
- **The announcements themselves** — that a loan opened, that a loan closed. An
  event already happened, cannot be amended, and two announcements of the same
  fact are the same announcement.

**The ratio is the point.** Three entities; everything else a value. Systems go
wrong in the opposite direction — a dozen entities, most of them for things that
are really just labels — and then every small change requires a migration, every
read requires a join, and every screen leaks a surrogate key.

### Aggregate boundaries

An aggregate is a consistency boundary: a cluster of concepts that must be
updated atomically, guarded by one root. Inside it, invariants hold at all times.
Across boundaries, they hold *eventually*.

The critical decision is what is **not** inside them.

**A copy does not contain its book.** A popular title may have four hundred
copies. If the book aggregate contained them all, correcting one title would mean
loading and re-saving four hundred items, and two librarians registering copies at
the same moment would collide on the same book. Instead a copy *refers to* its
book. Registering copy number 401 cannot corrupt the catalogue entry.

**A loan does not contain a copy or a borrower.** A loan is a lending fact. It
names the copy; it does not own it. Lending physically cannot alter a barcode or a
shelf status, because it does not have them.

So the rule across the entire domain:

> **Aggregates refer to each other by identity, never by containment.**

The payoff is that every transaction touches exactly one aggregate — which is what
makes scenario 3.3 (two borrowers, one copy, one winner) solvable at all, rather
than a race condition papered over with a retry loop.

### Invariants, and who guards each one

| Invariant | Guarded by | Immediately? |
|---|---|---|
| An ISBN must be structurally valid | The ISBN itself — an invalid one cannot exist | yes |
| No duplicate book in the catalogue | Adding a book, which checks first | yes |
| A copy must belong to a book and carry a barcode | Registering a copy | yes |
| At most one open loan per copy | Lending a copy | yes |
| The due date is thirty days out | Opening a loan | yes |
| The fee tier follows the days late | Closing a loan | yes |
| Shelf status matches loan state | Cataloguing, on hearing an announcement | **eventually** |

The first six live inside a single aggregate, so they hold unconditionally. The
seventh spans two contexts, which means it *cannot* live in either aggregate — so
it is eventually consistent by design, per Part I §4. A briefly-stale shelf flag
costs a librarian one refresh; merging the contexts to make it instantaneous would
cost the library its ability to ever run them separately.

### How this becomes JPA

The mapping is almost invisible, because value objects do the work.

```java
@Entity
public class Book {
    @EmbeddedId
    private BookId id;
    private String title;
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "isbn"))
    private Isbn isbn;

    Book() { }                       // package-private: JPA only

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

- **The no-arg constructor is package-private.** JPA needs it; application code
  cannot reach it. Cheapest possible guard against half-built aggregates.
- **`equals`/`hashCode` compare identity only.** `Book` does not compare title
  or ISBN. That is what "entity" means, and getting it wrong is a classic
  source of `Set` corruption in JPA.
- **No setters.** `Copy` exposes `makeAvailable()` / `makeUnavailable()`, never
  `setAvailable(boolean)`. The method name carries the intent, and callers
  cannot put a copy into an arbitrary state for an arbitrary reason.
- **`@AttributeOverride`** disambiguates the column when the same embeddable
  type appears twice (`Loan` holds both a `CopyId` and a `UserId`, and both
  records wrap a field named `id`).

Records work as `@Embeddable` value objects against both providers the project
tests on — EclipseLink 5.0.2 (GlassFish) and Hibernate 7.4 (WildFly Preview) —
from the same source.

## 2. Repository ↔ Jakarta Data

This is the part that changed most with Jakarta EE 11.

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

### Who actually implements it — and it is not the same answer twice

This is the part that is easy to get wrong, so it is worth being blunt:

> **Jakarta Data is a specification, not a library.** There is no
> `jakarta-data-impl.jar` you add to your POM. `jakarta.jakartaee-api` is
> `<scope>provided</scope>` — it hands you the annotations and interfaces at
> compile time and nothing else. The implementation is supplied by the
> **runtime**, which means the same four-line interface is realised by two
> completely different mechanisms depending on which server you deploy to.

This project is wired to demonstrate exactly that. The POM swaps the annotation
processor per profile:

| Profile | Server | Persistence provider | `annotationProcessorPaths` |
|---|---|---|---|
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
7. **Your `default` methods come along for free.** Because the generated class
   *implements* the interface, `LoanRepository.findByIdOrThrow` is simply
   inherited — the processor emits nothing for it. Domain-flavoured conveniences
   cost zero codegen.

### The trade-off, honestly

| | Compile-time codegen (Hibernate / WildFly) | Runtime reflection (EclipseLink / GlassFish) |
|---|---|---|
| Implementation | a `.java`/`.class` you can read and step into | an opaque proxy |
| Build requirement | processor on `annotationProcessorPaths` | nothing extra |
| Deployment cost | none — the class already exists | reflection and proxy construction at deploy |
| Debugging | breakpoints in generated source | breakpoints in a proxy |
| Provider coupling | generated class imports `org.hibernate.StatelessSession` | none in your build output |
| Where problems surface | at compile/deploy, earlier | at deploy or first invocation |

The coupling row deserves emphasis: `BookRepository_` is thoroughly
Hibernate-specific. **Your `BookRepository` is not.** The domain layer only ever
names the interface and never sees the generated class, which is precisely why
the identical source tree deploys to both servers and why the two CI profiles are
worth running. The port stays clean; only the adapter changes, and the adapter is
somebody else's problem.

Two practical consequences:

- If you build with the WildFly profile and then deploy that WAR to GlassFish,
  you ship Hibernate-specific generated classes into an EclipseLink runtime.
  Build per target, or keep the generated sources out of the artifact.
- `@jakarta.transaction.Transactional` is emitted on the generated class anyway,
  so putting it on the interface (as this sample does) is belt-and-braces rather
  than load-bearing on Hibernate — but it *is* load-bearing on EclipseLink, where
  nothing is generated. Keep it.

### What this replaced

Every Jakarta EE DDD project before Jakarta Data 1.0 wrote this by hand, in the
infrastructure layer:

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

Every one of those classes was pure translation — no domain logic, no business
value, just ceremony. Worse, the port and its adapter were two files that could
drift apart silently. Jakarta Data collapses them into one artifact that cannot
disagree with itself.

### Three techniques worth stealing

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
it costs zero infrastructure — it is an interface default method.

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

### Why `@Transactional` sits on the interface

It makes each repository method safe to call standalone, and when called from
inside an already-active use-case transaction it simply joins it (the default
`REQUIRED` behaviour). Your use case still owns the boundary — `RentBookUseCase`
saves the loan and fires the event atomically — but the repository does not
explode if someone injects it into a JAX-RS resource or a test without wrapping
it first.

### Where Jakarta Data goes next

The sample only uses `CrudRepository`. The spec has more when you need it:
`BasicRepository` adds batch operations, `DataRepository` lets you define an
entirely custom contract, derived query methods
(`findByTitleContainingIgnoreCase` and friends) cover ad-hoc finders without
writing JPQL, and `Page`/`Sort`/`CursoredPage` handle the listing and pagination
every real application eventually grows. When `OverdueFee` graduates from an
enum to a table so staff can adjust rates without a redeploy, its repository is
another four-line interface.

## 3. Domain events ↔ CDI events

### What the events are

The storm produced two facts that somebody else cares about:

- **A loan was opened.** Carries: which copy.
- **A loan was closed.** Carries: which copy.

Both carry exactly one thing, because that is all cataloguing needs in order to
update a shelf status. Not the borrower, not the due date, not the fee. The more
an announcement carries, the more its listeners come to depend on, and the harder
it becomes to change anything. **An event payload should be the minimum that lets
a subscriber act.**

A third fact is recognised but not yet announced: **an overdue fee was charged.**
Today the fee is simply recorded on the loan and nobody is told. The moment
billing, member notifications, or fee waivers become requirements, that fact needs
an audience — and it should become an announcement, not a call from lending into
some billing module that lending then has to know about.

**Why announcements rather than direct calls.** When a borrower takes a copy,
lending's job is to open a loan. It is not lending's job to know that cataloguing
keeps a shelf flag, that a future search index needs re-indexing, or that a
monthly report counts loans per day. Lending states what happened and moves on;
everyone else decides for themselves whether they care. This is precisely what
allows the two contexts to be split into separate services later without rewriting
either one.

### How they become CDI events

Jakarta EE has had a synchronous, type-safe, transaction-aware event bus since
CDI 1.0. It is a perfectly good domain event mechanism, and the sample uses it
directly — no framework, no outbox table, no broker.

The two events are records, because an event is a value object:

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

    public RentBookUseCase() { }                   // required by the CDI spec

    @Inject
    public RentBookUseCase(LoanRepository loanRepository,
                           CopyAvailabilityValidator copyAvailabilityValidator,
                           Event<LoanCreated> loanCreatedEvent,
                           Clock clock) { … }

    public void execute(CopyId copyId, UserId userId) {
        copyAvailabilityValidator.checkAvailable(copyId);
        var now = LocalDateTime.now(clock);
        loanRepository.save(new Loan(copyId, userId, now, LocalDate.now(clock).plusDays(30)));

        loanCreatedEvent.fire(new LoanCreated(copyId));
    }
}
```

The subscriber declares a parameter:

```java
@ApplicationScoped
public class DomainEventListener {

    public void onLoanCreated(@Observes LoanCreated event) { … }
    public void onLoanClosed(@Observes LoanClosed event) { … }
}
```

The properties that make this work as a DDD event mechanism:

- **Synchronous by default.** The observer runs inside the firing transaction, so
  the loan and the availability change commit together or not at all. No
  half-applied state.
- **Type-safe and compile-checked.** `Event<LoanCreated>` cannot fire a
  `LoanClosed` by accident; a string topic name can.
- **The publisher knows nothing about subscribers.** `RentBookUseCase` has no
  import from `library.catalog`. Add a second listener — audit log, search index,
  metrics — and the lending context does not change.
- **Swapping to `@ObservesAsync` or a real broker is a local change.** The event
  records and the `fire()` calls stay identical.

The `execute` body is four lines of orchestration: validate, construct the
aggregate, persist through the repository, announce what happened. No
transaction demarcation, no logging, no lifecycle code.

## 4. Domain services and application services ↔ CDI

Two building blocks that are easy to conflate, and worth separating before
looking at code.

An **application service** is the outside world's handle on the domain. It
carries no business rules of its own — it starts a transaction, asks the domain
to do something, persists the result, and announces what happened. There is one
per use case from Part 0, and nothing else.

A **domain service** holds a rule that does not belong to any single aggregate.
The classic case is a rule that needs to consult more than one aggregate, or a
capability the domain needs but cannot provide itself.

This sample has both, and one interesting judgement call:

| Class | Kind | Why |
|---|---|---|
| `AddBookToCatalogUseCase`, `RegisterBookCopyUseCase`, `RentBookUseCase`, `ReturnBookUseCase` | Application service | One per journey; pure orchestration |
| `CopyAvailabilityValidator` | **Domain service** | "At most one open loan per copy" is a rule, not orchestration — and it cannot live inside `Loan`, because a single loan cannot see the other loans against the same copy |
| `BookSearchService` | Domain service *port* | A capability the domain needs but cannot provide; the generic subdomain from Part I §1 |

The judgement call: `CopyAvailabilityValidator` is a domain rule, yet it sits in
`library.lending.application`, not `library.lending.domain`. Defensible either
way — it is invoked only from an application service and depends on a repository
— but if the availability rule ever grows (reservations, hold queues, per-member
limits) it belongs in the domain package, and the ArchUnit rules would not object
to moving it.

### 4.1 Application services ↔ the `@UseCase` stereotype

Every use case is annotated `@UseCase`, a CDI stereotype bundling three
annotations into one:

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

Read it as a *definition* of what a use case is in this application: a singleton,
running in a transaction, with entry/exit/exception logging applied by an
interceptor. The logging is a plain CDI interceptor bound by `@Logged` and
registered in `beans.xml`:

```xml
<interceptors>
    <class>library.shared.LoggedInterceptor</class>
</interceptors>
```

Stereotypes are underrated. Without one, every application service repeats three
annotations and inevitably forgets one — usually `@Transactional`, which fails at
runtime rather than at compile time. With one, "this class is a use case" is a
single, greppable, enforceable fact.

### 4.2 Domain services ↔ a CDI bean that owns a cross-aggregate rule

"At most one open loan per copy" (scenario 3.2) is a genuine domain rule, but it
cannot live inside `Loan`: a single loan has no way to see the *other* loans
against the same copy. A rule that must consult more than one instance is exactly
what a domain service is for.

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

One method, one rule, one reason to change. It reads the invariant straight out
of Part 0 — `isAvailable` is the Jakarta Data `@Query` from §2 — and it fails in
domain terms with `CopyNotAvailableException`, which as an unchecked
`DomainException` rolls the use case's transaction back.

### 4.3 Ports and adapters ↔ CDI + JAX-RS client

Journey J1 needs a title for an ISBN, resolved from an external service — the
generic subdomain from Part I §1. The domain declares the need:

```java
// library.catalog.domain — the port
public interface BookSearchService {
    BookInformation search(Isbn isbn);
}
```

No `jakarta.ws.rs` import, no `Client`, no status codes, no JSON. The adapter
supplies all of it:

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

The adapter's real job is **translation**: 404 → `BookNotFoundException`,
500 → `BookSearchException`, connection reset → `BookSearchException` wrapping
the cause. Wire-level concerns stop at the boundary; `AddBookToCatalogUseCase`
only ever sees domain exceptions.

(It also follows Open Library's 302 to `/books/{key}.json` manually, because the
JAX-RS client will not, and keeps `requestUri` around so a relative `Location`
header can be resolved against it. Real adapters are messy. That mess is exactly
what belongs in the infrastructure layer and nowhere else.)

Because the constructor accepts a base URL, the adapter is testable without a
network — which is what the WireMock tests exploit in Part III.

## 5. Cohesion in classes, decoupling between them

Two principles, applied consistently, produce most of the readability in this
codebase.

**Cohesion — every class has one reason to change.**

| Class | Its single job |
|---|---|
| `CopyAvailabilityValidator` | One method: decide whether a copy may be lent |
| `DomainEventListener` | React to other contexts' events |
| `OpenLibraryBookSearchService` | Translate HTTP into domain terms |
| `ClockProducer` | Supply the system clock |
| `RentBookUseCase` | Orchestrate one business transaction |

Nothing here is a 600-line service. No `execute` method in the sample exceeds
four statements, and every one of them reads as a sequence of domain operations
rather than as plumbing.

**Decoupling — dependencies point inward and are declared, not discovered.**

- **Constructor injection everywhere in main code.** The dependencies of a class
  are its constructor signature. Nothing is hidden in a field annotation, and
  every class is instantiable in a plain unit test. (`@Inject` on fields appears
  only in Arquillian tests, where the container requires it.)
- **Dependency inversion at the port.** `AddBookToCatalogUseCase` depends on the
  `BookSearchService` interface. `OpenLibraryBookSearchService` depends on the
  same interface. Neither knows the other exists; CDI wires them. ArchUnit fails
  the build if `application` ever imports `infrastructure`.
- **Identity references instead of object references** (§1 above), so
  aggregates cannot reach into each other.
- **Package-private constructors** as access control — `Book()`, `Copy()`,
  `Loan()`, and the use cases' CDI-mandated no-arg constructors are all invisible
  outside their package.
- **Unchecked domain exceptions** mean callers are not forced to catch what they
  cannot handle, and the container still rolls back.

## 6. Modules and ArchUnit: making the structure stick

The two bounded contexts and the shared kernel from Part I become three top-level
packages, each with the same three-layer shape:

```
library
├── shared                    shared kernel — depends on neither context
│   ├── UseCase               stereotype: @ApplicationScoped + @Transactional + @Logged
│   ├── Logged / LoggedInterceptor
│   ├── DomainException       base type for every domain failure
│   └── ClockProducer
├── catalog
│   ├── application           AddBookToCatalogUseCase, RegisterBookCopyUseCase, DomainEventListener
│   ├── domain                Book, Copy, BookRepository, CopyRepository, BookSearchService (port)
│   └── infrastructure        OpenLibraryBookSearchService (adapter)
└── lending
    ├── application           RentBookUseCase, ReturnBookUseCase, CopyAvailabilityValidator
    └── domain                Loan, LoanRepository, OverdueFee, LoanCreated, LoanClosed
```

Note what is absent: `lending` has no `infrastructure` package, because it needs
none — its only external dependency is the database, and Jakarta Data hides that
completely. There is also no `catalog.infrastructure.BookRepositoryImpl`, the
file every pre-Jakarta-Data DDD codebase carried.

The three "modules" are packages, which means nothing stops anyone from ignoring
them. So the sample ships **eight ArchUnit rules** that run as ordinary unit
tests during `mvn package` and fail the build on violation:

| # | Rule | Enforces |
|---|---|---|
| 1 | `..domain..` must not depend on `..application..` or `..infrastructure..` | Layer direction |
| 2 | `..domain..` must not depend on `jakarta.ws.rs..`, `jakarta.enterprise..`, `jakarta.inject..` | Domain framework-independence |
| 3 | `..application..` must not depend on `..infrastructure..` | Dependency inversion |
| 4 | `..application..` *must* depend on `..domain..` | Application services orchestrate the model |
| 5 | `library.shared..` must not depend on `catalog..` or `lending..` | Shared kernel neutrality |
| 6 | `library.lending..` must not depend on `library.catalog..` | Context isolation |
| 7 | `library.catalog..` must not depend on `library.lending..`, except `DomainEventListener` | The one named coupling |
| 8 | Any `*Repository` must reside in `..domain..` | Repository is a port |

Rule 7 is the interesting one:

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

A blanket ban would be a lie — the contexts *do* communicate. Whitelisting the
single class where the coupling lives makes the exception visible, named, and
reviewable, rather than letting it spread. **An architecture rule with a
documented exception is stronger than a rule with a silent one.**

### The compromise hiding in rule 2

Look at what is *not* banned: `jakarta.persistence`. `Book`, `Copy`, and `Loan`
are all `@Entity`; `Isbn` and `BarCode` are `@Embeddable`. The domain model is
JPA-annotated.

This is a real trade-off and the sample makes it deliberately. The pure
alternative — a separate persistence model with mappers — costs a parallel class
hierarchy and a mapping layer that must stay in sync with both. For most teams
that cost exceeds the benefit, and JPA annotations are declarative metadata
rather than behaviour: the domain still has no *runtime* dependency on the
provider, which is why the same WAR runs on EclipseLink and Hibernate.

The line drawn here is defensible — the domain must not depend on things that
*do something to it* (CDI scopes, interceptors, HTTP clients, request/response
objects); annotations that only describe storage are tolerated. But be honest
about the price: your aggregates are coupled to your ORM's mapping rules, which
is why every entity needs that package-private no-arg constructor and why
`@AttributeOverride` appears in domain classes.

---

# Part III — Proving the requirements: the scenario integration tests

The scenario tests live in `src/test/java/library/it/LibraryIT.java`. (`LibraryIT`
is the scenario suite; there is no class literally named `ScenarioIT`.) Its job
is to exercise the use cases end to end, inside a real container, against a real
database, and show that the Part 0 requirements hold.

## Coverage matrix

Every Part 0 scenario, in requirement order:

| Req | Scenario | Proved by | Status |
|---|---|---|---|
| 1.1 | Invalid ISBN refused | `Isbn` record constructor | ⚠️ no dedicated test |
| 1.2 | Duplicate ISBN → `BookAlreadyExistsException` | — | ❌ **not covered** |
| 1.3 | Happy path: ISBN → title → `Book` saved | — | ❌ **not covered** |
| 1.4 | Unknown ISBN → `BookNotFoundException` | `OpenLibraryBookSearchServiceTest` (WireMock) | ✅ |
| 1.5 | Upstream 500 → `BookSearchException` | `OpenLibraryBookSearchServiceTest` | ✅ |
| 1.6 | Network fault → `BookSearchException` | `OpenLibraryBookSearchServiceTest` | ✅ |
| 2.1 | Register a copy → on the shelf, available | — | ❌ **not covered** |
| 2.2 | Missing book or barcode refused | `@NotNull` + `Copy` constructor | ⚠️ no dedicated test |
| 2.R | A copy must belong to a *catalogued* book | — | ❌ **rule not implemented** |
| 3.1 | Rent an available copy → loan opens, copy leaves the shelf | `LibraryIT.testLibraryCrud` | ✅ |
| 3.2 | Rent an already-loaned copy → refused | `LibraryIT.testLibraryCrud` | ⚠️ assertion too loose |
| 3.3 | Two borrowers, one copy, one winner | — | ❌ **not covered** |
| 4.1 | Return → loan closes, copy back on the shelf | `LibraryIT.testLibraryCrud` | ✅ |
| 4.2 | Unknown loan → `LoanNotFoundException` | — | ❌ **not covered** |
| 4.3 | On-time return → no fee | `LibraryIT.testLibraryCrud` (implicit) | ✅ |
| 4.4 / 5 | Late return → `BEYOND_A_MONTH` fee | `LibraryIT.testOverdueReturn` | ✅ |
| 5 | All five fee tiers | `OverdueFeeTest` (unit) | ✅ |
| II §6 | All 8 architecture rules | `ArchitectureTest` | ✅ |

Roughly half the stated requirements are proved, and the lending side — the part
with the money and the contention — is the better-covered half.

### The gaps, stated plainly

**Journeys J1 and J2 have no scenario test at all.** `AddBookToCatalogUseCase` and
`RegisterBookCopyUseCase` are referenced by **no test anywhere** — grep confirms
they appear only in `src/main`. `LibraryIT` sets up its fixtures by constructing
aggregates directly:

```java
Book book = new Book("Effective Java", new Isbn("9780134685991"));
bookRepository.save(book);

Copy copy1 = new Copy(copyId, book.getId(), new BarCode("BC001"));
copyRepository.save(copy1);
```

That is reasonable *fixture* code, but it means the duplicate check, the
bibliographic lookup, and the save in J1 are never exercised in sequence, and
J2 is never exercised at all. The search adapter's error translation is well
covered by WireMock; the use case that orchestrates it is not. These are the
obvious next tests to write, and the `OpenLibraryBookSearchService(BASE_URL)`
constructor makes an in-container version straightforward.

**Requirement 2.R is stated but not implemented.** `RegisterBookCopyUseCase` does
this and nothing more:

```java
public void execute(@NotNull BookId bookId, @NotNull BarCode barCode) {
    copyRepository.save(new Copy(bookId, barCode));
}
```

There is no check that `bookId` names a book in the catalogue. Registering a copy
against a book that does not exist succeeds, and because aggregates reference each
other by identity with no foreign key (Part II §1), nothing else will catch it
either. This is a genuine gap between the stated requirement and the code — either
the rule should be enforced with a `bookRepository.existsById(bookId)` check, or
the domain expert should confirm that a copy may legitimately precede its
catalogue entry. It is a conversation, not a silent fix.

**Requirement 3.3 is asserted nowhere.** `@Version` is on `Copy`, so the mechanism
exists, but no test races two rentals. Optimistic locking that is never tested
tends to be discovered in production.

**Scenario 3.2's assertion is too loose:**

```java
assertThrows(Exception.class, () -> rentBookUseCase.execute(…));
```

`Exception.class` is far too loose. The test would still pass if renting failed
with a `NullPointerException`, a persistence error, or a validation failure —
none of which prove the copy was rejected *for being unavailable*. It should be
`assertThrows(CopyNotAvailableException.class, …)`.

## How the scenario test is built

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

Each `withTx` block is one unit of work, mirroring how a real request would be
scoped. One subtlety: `catch (Exception e)` does **not** catch `AssertionError`
(an `Error`), so AssertJ failures still fail the test — but a genuinely
unexpected runtime exception inside a block would be silently rolled back and the
test would pass. Worth tightening if you build on this.

**Eventual consistency.** Because the availability flag is updated by an event
observer across transaction boundaries, assertions poll rather than assume:

```java
await().atMost(5000, TimeUnit.MILLISECONDS).untilAsserted(() -> {
    var copyOptional = copyRepository.findById(copyId);
    assertThat(copyOptional).isPresent();
    assertThat(copyOptional.get().isAvailable()).isFalse();
});
```

**The context boundary is visible.** Renting requires converting between the two
`CopyId` types, and the fully-qualified name in the test is the seam showing
through:

```java
rentBookUseCase.execute(library.lending.domain.CopyId.of(copyId.id()), userId);
```

That awkwardness is a feature. If it were invisible, the contexts would already
be merged.

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

**Adapter tests** run at unit level with WireMock, exercising the real JAX-RS
stack against a stubbed Open Library on `localhost:8080` — four cases covering
the 302→200 happy path, 404, 500, and `Fault.CONNECTION_RESET_BY_PEER`. No
network, no container, fast.

---

# Part IV — Running the code

## Prerequisites

- **Java 21** (the POM sets `maven.compiler.release=21`)
- **Maven 3.9.x**
- **Git**
- Any editor — IntelliJ IDEA Community, or VS Code with the Java extension pack

Docker is not required. The container profiles download and unpack an app server
into `target/` for you.

## Clone and build

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
repository is realised reflectively by the runtime at deployment. Building with
the WildFly profile swaps in `org.hibernate.orm:hibernate-processor`, which
instead emits a compiled `BookRepository_` / `CopyRepository_` / `LoanRepository_`
implementation at compile time:

```bash
mvn -B -P wildfly compiler:compile
# then inspect target/generated-sources/annotations/library/catalog/domain/
```

Part II §2 has the full runtime-vs-compile-time comparison.

## Run the scenario tests on GlassFish 8

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

## Run the scenario tests on WildFly Preview

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

## Continuous integration

Three GitHub Actions workflows, all on Java 21 / Temurin with a Maven cache:

| Workflow | Command |
|---|---|
| `build.yml` | `mvn -B package` |
| `arq-glassfish-managed.yml` | `mvn -B -q clean verify -Parq-glassfish-managed` |
| `arq-wildfly-managed.yml` | `mvn -B -q clean verify -Parq-wildfly-managed` |

The GlassFish job uploads `target/glassfish*/glassfish/domains/domain1/logs/server.log`
as an artifact on failure, which is usually the only way to see what the container
actually complained about.

## Known issues

The README carries both of these; neither is a design problem.

1. **EclipseLink on GlassFish.** An EclipseLink defect broke tests on GlassFish
   8.0.0 — tracked as
   [eclipselink#2656](https://github.com/eclipse-ee4j/eclipselink/issues/2656).
   The project now pins GlassFish 8.0.5 / EclipseLink 5.0.2.
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

## There is no HTTP API (yet)

Worth saying before you deploy and wonder: `src/main` contains **no `@Path` or
`@ApplicationPath` class**. The WAR packages and deploys cleanly, but nothing is
exposed over HTTP. To exercise the system today you run the integration tests, or
inject the use cases into your own component.

Adding a REST layer is a small, well-defined next step, and the ArchUnit rules
already tell you where it goes: a new `library.catalog.interfaces` (or
`..adapter.in.web`) package that injects `AddBookToCatalogUseCase` and friends,
maps `DomainException` subtypes to HTTP statuses via an `ExceptionMapper`, and
never touches `..domain..` types directly in its signatures. Rule 2 will fail the
build the moment a `jakarta.ws.rs` import leaks inward — which is exactly what it
is there for.

---

# Takeaways

**Strategic — before any code**

- **Classify subdomains first.** Core gets your best modelling effort, supporting
  gets the simplest correct thing, generic gets bought or integrated behind a
  boundary. Lending is core here; cataloguing is supporting; bibliographic lookup
  is generic and sits behind an adapter precisely so it can be swapped.
- **A subdomain is not a bounded context.** One partitions the business, the other
  partitions the model. They often coincide — and here the generic subdomain
  deliberately does *not* get a context of its own.
- **Split contexts by rate and reason for change**, not by noun. A fee-tier change
  must never risk catalogue metadata.
- **The same word meaning two different things is the boundary signal.** "Copy" is
  a physical asset in cataloguing and a borrowed identifier in lending. Do not
  glossary that away; build the boundary there.
- **Healthy context maps have one-way flows.** Two crossed one-way flows —
  identity downstream, facts upstream — can each be replaced independently. A
  bidirectional *data* dependency never can.
- **Pin the ubiquitous language down and hunt its traps.** "Available" needed an
  exact definition; "return" turned out to be both an act and a date, and only the
  date determines the fee.
- **Name the single place two contexts meet** instead of pretending they don't.

**Tactical — in code**

- **Entity vs. value object, one question:** would two instances with the same
  attributes be the same thing? Three entities here; everything else a record.
- **Identifiers are value objects.** Wrapping them — and giving lending a `CopyId`
  that cannot mint its own UUID — puts ownership in the type system.
- **Aggregates reference each other by identity, never by containment.** Zero JPA
  associations buys small transactions, no lazy-loading traps, and
  compiler-enforced context isolation.
- **A rule that must consult more than one instance is a domain service**, not an
  entity method. One loan cannot see the other loans against the same copy, so
  "at most one open loan" lives in `CopyAvailabilityValidator`.
- **Keep application services empty of rules.** One per journey: validate,
  construct, persist, announce.
- **Domain events carry the minimum a subscriber needs.** One `CopyId`, not a
  whole `Loan`.
- **Jakarta Data turns the repository from infrastructure you own into a domain
  contract you declare:** four lines, in `..domain..`, with no implementation
  class in your source tree. The biggest quality-of-life change Jakarta EE 11
  brings to DDD.
- **Jakarta Data is a spec, not a library.** The interface is yours; the
  implementation is your runtime's. EclipseLink proxies it reflectively at
  deployment; Hibernate compiles a `BookRepository_` via its annotation
  processor. Reference only the interface, and build for the server you deploy to.
- `@Query` returning `boolean`, plus a `default findByIdOrThrow` throwing a
  `DomainException`, are enough to make a generated repository feel like domain
  code rather than a DAO.
- **CDI stereotypes** (`@UseCase`) collapse scope + transaction + logging into one
  greppable, enforceable concept.
- **CDI events are a complete domain event mechanism:** synchronous, type-safe,
  transaction-sharing, and swappable for async or a broker without touching
  publishers.
- **Constructor injection plus package-private constructors** is most of your
  encapsulation budget, spent well.
- **ArchUnit** turns "we agreed on this architecture" into "the build fails if you
  break it" — eight rules, including one that whitelists a single class so the
  exception stays visible.
- **JPA annotations in the domain are a compromise, not a solution.** Know which
  one you are making, write it down, and ban the annotations that would make it
  worse.

**Verification**

- **Write the requirements down before reading the code.** Doing so surfaced a
  stated rule nobody had implemented (a copy must belong to a catalogued book)
  and an untested one (two borrowers racing for one copy) — neither visible from
  the code alone.
- **Map every scenario ID to a test.** The coverage matrix is the deliverable; the
  ❌ rows are the backlog.
- **Run the same WAR against two providers in CI.** The only honest proof that the
  domain is provider-agnostic.
- **Inject the real Jakarta Data repositories** into scenario tests rather than
  faking them — the point is that the generated implementation works.
- **Assert the specific domain exception.** `assertThrows(Exception.class, …)`
  proves almost nothing.
- **Record a disabled test's reason on the annotation**, with the upstream issue
  link.

Full source: [github.com/hantsy/jakartaee-ddd-example](https://github.com/hantsy/jakartaee-ddd-example).
