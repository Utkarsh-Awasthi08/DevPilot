package devPilot.backend.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Tracks the last-indexed GitHub blob SHA per file so a re-index can diff against it and skip
 * files that haven't changed, instead of re-embedding the whole repository every run.
 */
@Entity
@Table(name = "indexed_files", uniqueConstraints = @UniqueConstraint(columnNames = { "repository_id", "file_path" }))
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class IndexedFile {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "file_path", nullable = false, length = 1000)
    private String filePath;

    @Column(name = "blob_sha", nullable = false, length = 64)
    private String blobSha;

    @Column(name = "chunk_count", nullable = false)
    @Builder.Default
    private int chunkCount = 0;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (updatedAt == null) {
            updatedAt = Instant.now();
        }
    }
}
