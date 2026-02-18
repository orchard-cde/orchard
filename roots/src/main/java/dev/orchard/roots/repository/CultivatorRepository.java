package dev.orchard.roots.repository;

import dev.orchard.roots.entity.CultivatorEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CultivatorRepository extends JpaRepository<CultivatorEntity, UUID> {

    Optional<CultivatorEntity> findByUsername(String username);

    Optional<CultivatorEntity> findByEmail(String email);

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);
}
