package devPilot.backend.services.indexing;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.springframework.stereotype.Component;

/**
 * Manages per-user indexing concurrency to ensure a user never has more than one
 * active indexing job hitting the GitHub API or Gemini rate limits simultaneously.
 * Additional indexing requests are placed in a FIFO queue.
 */
@Component
public class UserIndexingCoordinator {

    // Tracks the currently active indexing repository for each user
    private final ConcurrentHashMap<UUID, UUID> activeUserJobs = new ConcurrentHashMap<>();

    // Tracks the FIFO queue of pending repository indexing requests for each user
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<UUID>> userQueues = new ConcurrentHashMap<>();

    /**
     * Checks if a repository is already active or queued for the given user.
     * Prevents duplicate trigger clicks from queueing the same repository multiple times.
     */
    public boolean isRepoQueuedOrActive(UUID userId, UUID repoId) {
        if (repoId.equals(activeUserJobs.get(userId))) {
            return true;
        }
        ConcurrentLinkedQueue<UUID> queue = userQueues.get(userId);
        return queue != null && queue.contains(repoId);
    }

    public UUID getActiveJob(UUID userId) {
        return activeUserJobs.get(userId);
    }

    /**
     * Attempts to start an indexing job immediately. If the user has no active jobs,
     * registers this repo as active and returns true. Otherwise, returns false.
     */
    public synchronized boolean canStartImmediately(UUID userId, UUID repoId) {
        if (activeUserJobs.containsKey(userId)) {
            return false;
        }
        activeUserJobs.put(userId, repoId);
        return true;
    }

    /**
     * Enqueues a repository for the user to be processed once their active job finishes.
     */
    public void enqueue(UUID userId, UUID repoId) {
        userQueues.computeIfAbsent(userId, k -> new ConcurrentLinkedQueue<>()).offer(repoId);
    }

    /**
     * Pops the next queued repository for the user, marking it as the new active job.
     * Returns Optional.empty() and clears the active slot if the queue is empty.
     */
    public synchronized Optional<UUID> pollNext(UUID userId) {
        ConcurrentLinkedQueue<UUID> queue = userQueues.get(userId);
        if (queue != null && !queue.isEmpty()) {
            UUID nextRepoId = queue.poll();
            activeUserJobs.put(userId, nextRepoId);
            return Optional.of(nextRepoId);
        }
        activeUserJobs.remove(userId);
        return Optional.empty();
    }

    /**
     * Forcibly clears the active job slot for a user, useful during unhandled crash recovery.
     */
    public void clearActive(UUID userId) {
        activeUserJobs.remove(userId);
    }
}
