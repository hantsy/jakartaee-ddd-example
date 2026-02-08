package library.catalog.domain;

import jakarta.data.repository.CrudRepository;
import jakarta.data.repository.Repository;
import jakarta.transaction.Transactional;

@Repository
@Transactional
public interface CopyRepository extends CrudRepository<Copy, CopyId> {
}
