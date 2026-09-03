-- Tracks the last-indexed GitHub blob SHA per file, so a re-index can diff against it and skip
-- files that haven't changed instead of re-embedding the whole repository every run.
CREATE TABLE indexed_files (
    id UUID PRIMARY KEY,
    repository_id UUID NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    blob_sha VARCHAR(64) NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_indexed_files_repo_path UNIQUE (repository_id, file_path),
    CONSTRAINT fk_indexed_files_repository
        FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE
);

CREATE INDEX idx_indexed_files_repository_id ON indexed_files (repository_id);
