package dev.orchard.roots.repository;

import dev.orchard.roots.entity.SecretEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecretRepository extends JpaRepository<SecretEntity, UUID> {
    List<SecretEntity> findByCultivatorId(UUID cultivatorId);
    Optional<SecretEntity> findByCultivatorIdAndName(UUID cultivatorId, String name);
    List<SecretEntity> findByCultivatorIdAndNameIn(UUID cultivatorId, Collection<String> names);

    @Query("select s.vaultKey from SecretEntity s")
    List<String> findAllVaultKeys();
}
