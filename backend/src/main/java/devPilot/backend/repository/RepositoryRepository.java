package devPilot.backend.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import devPilot.backend.entity.Repository;

public interface RepositoryRepository extends JpaRepository<Repository, UUID> {
    List<Repository> findByUserIdOrderByFullNameAsc(UUID userId);

    Optional<Repository> findByIdAndUserId(UUID id, UUID userId);

    Optional<Repository> findByUserIdAndGithubRepoId(UUID userId, Long githubRepoId);

    /**
     * Atomically transitions a repository into INDEXING only if it isn't already indexing.
     * A single conditional UPDATE closes the check-then-act race that a separate
     * find-then-save would leave open between two concurrent index requests.
     * Returns the number of rows updated: 0 means the repo was already indexing (or not owned).
     */
    @Modifying
    @Query("UPDATE Repository r SET r.indexStatus = devPilot.backend.entity.IndexStatus.INDEXING, "
            + "r.filesProcessed = 0, r.filesTotal = 0, r.chunkCount = 0, r.errorMessage = null, "
            + "r.updatedAt = :now "
            + "WHERE r.id = :id AND r.userId = :userId AND r.indexStatus <> devPilot.backend.entity.IndexStatus.INDEXING")
    int tryStartIndexing(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query("UPDATE Repository r SET r.indexStatus = :newStatus, r.errorMessage = :errorMessage "
            + "WHERE r.indexStatus = :oldStatus")
    int updateStatusByStatus(@Param("oldStatus") devPilot.backend.entity.IndexStatus oldStatus,
                             @Param("newStatus") devPilot.backend.entity.IndexStatus newStatus,
                             @Param("errorMessage") String errorMessage);
}