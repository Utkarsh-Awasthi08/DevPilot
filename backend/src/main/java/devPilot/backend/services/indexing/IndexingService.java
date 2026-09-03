package devPilot.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.client.HttpClientErrorException;

import devPilot.backend.entity.IndexStatus;
import devPilot.backend.entity.IndexedFile;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.exceptions.ExternalServiceException;
import devPilot.backend.exceptions.NotFoundException;
import devPilot.backend.exceptions.UserFacingException;
import devPilot.backend.repository.IndexedFileRepository;
import devPilot.backend.repository.RepositoryRepository;
import devPilot.backend.services.UserService;
import devPilot.backend.services.ai.RagSettings;
import devPilot.backend.services.github.GitHubRateLimiter;
import devPilot.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 100;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private final RepositoryRepository repositoryRepository;
    private final IndexedFileRepository indexedFileRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

    /** One entry from the GitHub tree API: enough to detect whether a file's content changed. */
    private record GitEntry(String path, String sha, long size) {
    }

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    @Transactional
    public Repository startIndexing(UUID repoId, UUID userId) {
        // Verify ownership/existence first (also gives a clean 404 for a bad id).
        repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        // A single conditional UPDATE, not a separate find-then-save: this is what actually
        // closes the race between two concurrent index requests for the same repo. Whichever
        // request's UPDATE commits first wins; the other sees 0 rows affected below.
        int updated = repositoryRepository.tryStartIndexing(repoId, userId, Instant.now());
        if (updated == 0) {
            throw new BadRequestException("Repository is already being indexed");
        }

        return repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId) {
        try {
            doIndex(repoId, userId);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, friendlyMessage(ex));
        }
    }

    /**
     * The message stored in Repository.errorMessage is shown directly in the UI, so only ever
     * store a message we know is safe (see UserFacingException) — anything else (an arbitrary
     * exception from a library we haven't specifically wrapped) falls back to a generic
     * sentence, with the real exception already logged in full above by the caller.
     */
    private static String friendlyMessage(Exception ex) {
        if (ex instanceof UserFacingException) {
            return ex.getMessage();
        }
        return "Indexing failed due to an unexpected error. Please try again.";
    }

    /**
     * Lets callers (e.g. the controller, if the @Async dispatch itself throws before
     * indexAsync's own try/catch ever runs) explicitly fail a job stuck in INDEXING so it
     * isn't permanently blocked from retry.
     */
    public void failIndexing(UUID repoId, String reason) {
        markFailed(repoId, reason);
    }


    private void doIndex(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));

        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        if (tree != null && Boolean.TRUE.equals(tree.get("truncated"))) {
            log.warn("GitHub tree for {} is truncated; indexing will be incomplete for this repo",
                    repo.getFullName());
        }
        List<GitEntry> entries = listIndexableEntries(tree);

        // Diff against what we indexed last time by GitHub's own blob SHA per file, instead of
        // wiping and re-embedding the whole repository on every run: a file whose SHA is
        // unchanged is guaranteed byte-identical to what's already embedded, so it's skipped
        // entirely (no fetch, no chunk, no embedding call).
        Map<String, IndexedFile> previouslyIndexed = indexedFileRepository.findByRepositoryId(repoId).stream()
                .collect(Collectors.toMap(IndexedFile::getFilePath, f -> f));
        Set<String> currentPaths = entries.stream().map(GitEntry::path).collect(Collectors.toSet());
        List<String> removedPaths = previouslyIndexed.keySet().stream()
                .filter(path -> !currentPaths.contains(path))
                .toList();

        List<GitEntry> toEmbed = new ArrayList<>();
        List<String> changedPaths = new ArrayList<>();
        int unchangedChunks = 0;
        for (GitEntry entry : entries) {
            IndexedFile prior = previouslyIndexed.get(entry.path());
            if (prior != null && prior.getBlobSha().equals(entry.sha())) {
                unchangedChunks += prior.getChunkCount();
            } else {
                toEmbed.add(entry);
                if (prior != null) {
                    changedPaths.add(entry.path());
                }
            }
        }

        // Clear vectors only for files that are being re-embedded (old content, about to be
        // replaced) or removed entirely — a file embedded for the first time has nothing to
        // clear, so a fresh index never pays for a wasted delete-by-path over the whole tree.
        List<String> pathsToClear = new ArrayList<>(removedPaths);
        pathsToClear.addAll(changedPaths);
        if (!pathsToClear.isEmpty()) {
            deleteVectorsForPaths(repoId.toString(), pathsToClear);
            indexedFileRepository.deleteByRepositoryIdAndFilePathIn(repoId, pathsToClear);
        }

        int alreadyDone = entries.size() - toEmbed.size();
        updateProgress(repoId, entries.size(), alreadyDone, unchangedChunks, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        List<IndexedFile> pendingRecords = new ArrayList<>();
        int processed = alreadyDone;
        int totalChunks = unchangedChunks;
        int skippedFiles = 0;

        for (GitEntry entry : toEmbed) {
            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), entry.path());
                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), entry.path(), content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                pendingRecords.add(IndexedFile.builder()
                        .repositoryId(repoId)
                        .filePath(entry.path())
                        .blobSha(entry.sha())
                        .chunkCount(chunks.size())
                        .build());
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    flushBatch(batch, pendingRecords);
                }
            } catch (Exception ex) {
                skippedFiles++;
                log.warn("Skipping file {} in {}: {}", entry.path(), repo.getFullName(), ex.getMessage());
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == entries.size()) {
                updateProgress(repoId, entries.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (!batch.isEmpty()) {
            flushBatch(batch, pendingRecords);
        }

        markReady(repoId, entries.size(), processed, totalChunks, skippedFiles, repo.getFullName());
    }

    /**
     * Only records a file as indexed once its chunks are actually persisted — if the vector
     * save fails and the whole job is aborted, these files must still look "changed" on the
     * next attempt rather than being silently skipped forever.
     */
    private void flushBatch(List<Document> batch, List<IndexedFile> pendingRecords) {
        saveBatchWithRetry(batch);
        indexedFileRepository.saveAll(pendingRecords);
        batch.clear();
        pendingRecords.clear();
    }

    private void saveBatchWithRetry(List<Document> batch) {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                vectorStore.add(batch);
                return;
            } catch (Exception e) {
                if (isRateLimited(e)) {
                    log.warn("Rate limit hit (429). Retrying after 20 seconds...");
                    try {
                        Thread.sleep(20000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Interrupted while waiting to retry vector batch save", ie);
                    }
                } else {
                    throw e;
                }
            }
        }
        // Don't silently drop these documents and let the caller report READY with a chunk
        // count that includes vectors which were never actually persisted — fail the job.
        throw new UserFacingException(
                "Failed to save indexed content after several retries. Please try again.");
    }

    private static boolean isRateLimited(Exception e) {
        if (e instanceof ExternalServiceException extEx && extEx.getUpstreamStatus() == 429) {
            return true;
        }
        if (e instanceof HttpClientErrorException httpEx && httpEx.getStatusCode().value() == 429) {
            return true;
        }
        return e.getMessage() != null && e.getMessage().contains("429");
    }


    @SuppressWarnings("unchecked")
    private List<GitEntry> listIndexableEntries(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> rawEntries = (List<Map<String, Object>>) tree.get("tree");
        return rawEntries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> new GitEntry(
                        String.valueOf(entry.get("path")),
                        String.valueOf(entry.get("sha")),
                        entry.get("size") instanceof Number n ? n.longValue() : 0L))
                .toList();
    }

    private void deleteVectorsForPaths(String repoId, List<String> paths) {
        try {
            var b = new FilterExpressionBuilder();
            List<Object> pathValues = new ArrayList<>(paths);
            var filter = b.and(b.eq(RagSettings.METADATA_REPO_ID, repoId), b.in("filePath", pathValues)).build();
            vectorStore.delete(filter);
        } catch (Exception ex) {
            // Swallowing this used to be silent data corruption: re-indexing would proceed on
            // top of the un-deleted stale vectors with no signal to anyone. Fail the job
            // instead — better an honest failure than silently degraded chat answers later.
            throw new UserFacingException(
                    "Could not clear outdated indexed content before re-indexing. Please try again.", ex);
        }
    }

    @Transactional
    protected void updateProgress(
            UUID repoId,
            int total,
            int processed,
            int chunks,
            IndexStatus status,
            String error) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setFilesTotal(total);
            repo.setFilesProcessed(processed);
            repo.setChunkCount(chunks);
            repo.setIndexStatus(status);
            repo.setErrorMessage(error);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

    @Transactional
    protected void markReady(
            UUID repoId, int totalFiles, int processedFiles, int totalChunks, int skippedFiles, String fullName) {
        // Skipped files used to be invisible: the repo would report READY looking identical to
        // a fully clean run. errorMessage is repurposed here as a soft notice (not a failure)
        // so the frontend can distinguish "indexed cleanly" from "indexed, but incomplete".
        String note = skippedFiles > 0
                ? skippedFiles + " file(s) could not be fetched from GitHub and were skipped. "
                        + "Chat answers may be based on an incomplete index."
                : null;
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(note);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        if (skippedFiles > 0) {
            log.warn("Indexed {} files ({} chunks) for {} with {} file(s) skipped",
                    processedFiles, totalChunks, fullName, skippedFiles);
        } else {
            log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
        }
    }

    @Transactional
    protected void markFailed(UUID repoId, String message) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.FAILED);
            repo.setErrorMessage(message != null && message.length() > 2000
                    ? message.substring(0, 2000)
                    : message);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
    }

}