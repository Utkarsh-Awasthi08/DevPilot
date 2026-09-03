package devPilot.backend.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import devPilot.backend.entity.IndexedFile;

public interface IndexedFileRepository extends JpaRepository<IndexedFile, UUID> {
    List<IndexedFile> findByRepositoryId(UUID repositoryId);

    void deleteByRepositoryIdAndFilePathIn(UUID repositoryId, Collection<String> filePaths);
}
