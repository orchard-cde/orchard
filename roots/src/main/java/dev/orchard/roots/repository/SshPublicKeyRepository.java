package dev.orchard.roots.repository;

import dev.orchard.roots.entity.SshPublicKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SshPublicKeyRepository extends JpaRepository<SshPublicKeyEntity, UUID> {
    List<SshPublicKeyEntity> findByCultivatorId(UUID cultivatorId);
    Optional<SshPublicKeyEntity> findByIdAndCultivatorId(UUID id, UUID cultivatorId);
    @Transactional
    void deleteByIdAndCultivatorId(UUID id, UUID cultivatorId);
}
