package devPilot.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import devPilot.backend.dto.IndexStatusResponse;
import devPilot.backend.dto.RepositoryResponse;
import devPilot.backend.entity.Repository;
import devPilot.backend.exceptions.BadRequestException;
import devPilot.backend.security.CurrentUser;
import devPilot.backend.services.RepoService;
import devPilot.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
@Slf4j
public class RepoController {

    private final CurrentUser currentUser;
    private final RepoService repoService;

    private final IndexingService indexingService;

    @GetMapping
    public List<RepositoryResponse> list(
            @RequestParam(name = "refresh", defaultValue = "true") boolean refresh) {
        UUID userId = currentUser.require().getId();
        if (refresh) {
            return repoService.syncAndListRepos(userId);
        }
        return repoService.listStored(userId);
    }

    @GetMapping("/{id}")
    public RepositoryResponse get(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        return repoService.toResponse(repoService.requireOwned(id, userId));
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        Repository repo = indexingService.startIndexing(id, userId);
        try {
            indexingService.indexAsync(id, userId);
        } catch (Exception ex) {
            // If the @Async dispatch itself throws (e.g. the executor's queue is full) before
            // indexAsync's own try/catch ever runs, the repo would otherwise be left stuck at
            // INDEXING forever with no way to retry.
            log.error("Failed to dispatch indexing job for repo {}", id, ex);
            indexingService.failIndexing(id, "Failed to start indexing job: " + ex.getMessage());
            throw new BadRequestException("Failed to start indexing job. Please try again.");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        return repoService.status(id, userId);
    }

}