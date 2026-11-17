package repoLens.backend.services.github;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class GithubApiClient {

    private static final String API_BASE = "https://api.github.com";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient.Builder restClientBuilder;
    private final GitHubRateLimiter rateLimiter;

    public List<Map<String, Object>> listUserRepos(String accessToken) {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (true) {
            final int currentPage = page;
            try {
                List<Map<String, Object>> pageRepos = client(accessToken)
                        .get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/user/repos")
                                .queryParam("affiliation", "owner,collaborator,organization_member")
                                .queryParam("sort", "updated")
                                .queryParam("per_page", 100)
                                .queryParam("page", currentPage)
                                .build())
                        .retrieve()
                        .body(LIST_MAP);
                if (pageRepos == null || pageRepos.isEmpty()) {
                    break;
                }
                all.addAll(pageRepos);
                if (pageRepos.size() < 100) {
                    break;
                }
                page++;
            } catch (RestClientResponseException ex) {
                throw handleRateLimit(ex, "listing repositories");
            }
        }
        return all;
    }

    public Map<String, Object> getRepoTree(String accessToken, String owner, String repo, String branch) {
        try {
            return client(accessToken)
                    .get()
                    .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                    .retrieve()
                    .body(MAP);
        } catch (RestClientResponseException ex) {
            throw handleRateLimit(ex, "fetching repo tree");
        }
    }

    public String getFileContent(String accessToken, String owner, String repo, String path) {
        try {
            Map<String, Object> body = client(accessToken)
                    .get()
                    .uri("/repos/{owner}/{repo}/contents/{path}", owner, repo, path)
                    .retrieve()
                    .body(MAP);
            if (body == null) {
                return null;
            }
            Object encoding = body.get("encoding");
            Object content = body.get("content");
            if (content == null) {
                return null;
            }
            if ("base64".equals(String.valueOf(encoding))) {
                String raw = String.valueOf(content).replaceAll("\\s", "");
                return new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8);
            }
            return String.valueOf(content);
        } catch (RestClientResponseException ex) {
            throw handleRateLimit(ex, "fetching file contents");
        }
    }

    private IllegalStateException handleRateLimit(RestClientResponseException ex, String action) {
        if (ex.getStatusCode().is4xxClientError() && ex.getStatusCode().value() != 401) {
            long retryAfterMs = retryDelayMs(ex.getResponseHeaders());
            if (retryAfterMs > 0) {
                rateLimiter.pauseForRetryAfter(retryAfterMs / 1000L);
            }
            return new IllegalStateException(
                    "GitHub API rate limit reached while " + action + ". Wait a bit and retry indexing. " + ex.getMessage(), ex);
        }
        return new IllegalStateException("GitHub API request failed while " + action + ": " + ex.getMessage(), ex);
    }

    private long retryDelayMs(HttpHeaders headers) {
        if (headers == null) {
            return 0L;
        }
        String retryAfter = headers.getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter) * 1000L;
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        String resetHeader = headers.getFirst("X-RateLimit-Reset");
        if (resetHeader != null) {
            try {
                long resetEpochSeconds = Long.parseLong(resetHeader);
                long deltaMs = (resetEpochSeconds * 1000L) - System.currentTimeMillis();
                return Math.max(0L, deltaMs + 1000L);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return 0L;
    }

    private RestClient client(String accessToken){
        return restClientBuilder
                .baseUrl(API_BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "DevPilot")
                .build();
    }
}