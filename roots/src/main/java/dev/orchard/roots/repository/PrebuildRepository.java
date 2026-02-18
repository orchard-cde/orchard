package dev.orchard.roots.repository;

import dev.orchard.roots.entity.PrebuildEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PrebuildRepository extends JpaRepository<PrebuildEntity, UUID> {

    /**
     * Find a prebuild by repository URL, branch, and state.
     * Used primarily to find RIPE prebuilds for a given repo+branch.
     */
    Optional<PrebuildEntity> findByRepositoryUrlAndBranchAndState(
        String repositoryUrl, String branch, String state);

    /**
     * Find all prebuilds for a given repository URL and branch.
     */
    List<PrebuildEntity> findByRepositoryUrlAndBranch(String repositoryUrl, String branch);

    /**
     * Find all prebuilds in a given state.
     */
    List<PrebuildEntity> findByState(String state);
}
