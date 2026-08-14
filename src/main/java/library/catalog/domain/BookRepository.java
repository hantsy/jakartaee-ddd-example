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
