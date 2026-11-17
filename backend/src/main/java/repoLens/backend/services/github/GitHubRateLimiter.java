package repoLens.backend.services.github;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simple delay helper to stay under GitHub API secondary rate limits during indexing.
 */
@Component
public class GitHubRateLimiter {

    private final long delayMs;

    public GitHubRateLimiter(@Value("${app.github.api-delay-ms:250}") long delayMs) {
        this.delayMs = Math.max(0, delayMs);
    }

    public void pause() {
        sleep(delayMs);
    }

    public void pauseForRetryAfter(long seconds) {
        sleep(Math.max(delayMs, seconds * 1000L + 1000L));
    }

    private void sleep(long sleepMs) {
        if (sleepMs <= 0) {
            return;
        }
        try {
            Thread.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while rate limiting", e);
        }
    }
}