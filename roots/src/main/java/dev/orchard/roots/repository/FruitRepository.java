package dev.orchard.roots.repository;

import dev.orchard.roots.entity.FruitEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FruitRepository extends JpaRepository<FruitEntity, UUID> {

    List<FruitEntity> findByGroveId(UUID groveId);

    List<FruitEntity> findByGroveIdAndState(UUID groveId, String state);
}
