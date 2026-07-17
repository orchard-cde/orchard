package dev.orchard.roots.repository;

import dev.orchard.core.model.BeeState;
import dev.orchard.roots.entity.BeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BeeRepository extends JpaRepository<BeeEntity, UUID> {
    List<BeeEntity> findByGroveId(UUID groveId);
    List<BeeEntity> findByGroveIdAndState(UUID groveId, BeeState state);
}
