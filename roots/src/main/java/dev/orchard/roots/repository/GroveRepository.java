package dev.orchard.roots.repository;

import dev.orchard.core.model.GroveState;
import dev.orchard.roots.entity.GroveEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GroveRepository extends JpaRepository<GroveEntity, UUID> {

    List<GroveEntity> findByCultivatorId(UUID cultivatorId);

    List<GroveEntity> findByCultivatorIdAndState(UUID cultivatorId, GroveState state);

    List<GroveEntity> findByState(GroveState state);

    @Query("SELECT g FROM GroveEntity g WHERE g.state IN ('FLOURISHING', 'GROWING', 'PLANTING')")
    List<GroveEntity> findActiveGroves();

    @Query("SELECT g FROM GroveEntity g WHERE g.cultivatorId = :cultivatorId AND g.repositoryUrl = :repoUrl AND g.branch = :branch")
    List<GroveEntity> findByCultivatorAndRepo(UUID cultivatorId, String repoUrl, String branch);
}
