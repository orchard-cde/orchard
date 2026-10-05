package dev.orchard.roots.repository;

import dev.orchard.core.model.BeeState;
import dev.orchard.roots.entity.BeeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BeeRepository extends JpaRepository<BeeEntity, UUID> {
    List<BeeEntity> findByGroveId(UUID groveId);
    List<BeeEntity> findByGroveIdAndState(UUID groveId, BeeState state);

    /**
     * Deletes the Bee only if its recorded state is one of {@code removableStates}, as a single
     * statement so a concurrently committed state change cannot be missed between check and
     * delete.
     *
     * @return rows deleted: 1 when the Bee matched, 0 otherwise
     */
    @Modifying
    @Query("DELETE FROM BeeEntity b WHERE b.id = :beeId AND b.groveId = :groveId "
         + "AND b.state IN :removableStates")
    int deleteRemovable(@Param("beeId") UUID beeId,
                        @Param("groveId") UUID groveId,
                        @Param("removableStates") Collection<BeeState> removableStates);
}
