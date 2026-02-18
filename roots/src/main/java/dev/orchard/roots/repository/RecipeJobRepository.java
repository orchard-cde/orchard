package dev.orchard.roots.repository;

import dev.orchard.roots.entity.RecipeJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RecipeJobRepository extends JpaRepository<RecipeJobEntity, UUID> {

    /**
     * Find all recipe application jobs for a given grove.
     */
    List<RecipeJobEntity> findByGroveId(UUID groveId);

    /**
     * Find all recipe application jobs for a grove in a given state.
     */
    List<RecipeJobEntity> findByGroveIdAndState(UUID groveId, String state);
}
