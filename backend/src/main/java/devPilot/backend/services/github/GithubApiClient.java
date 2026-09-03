package devPilot.backend.services.github;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriUtils;

import devPilot.backend.exceptions.ExternalServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class GithubApiClient {

    private static final String API_BASE = "https://api.github.com";

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP = new ParameterizedTypeReference<>() {
    };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {
    };

    private final RestClient.Builder restClientBuilder;

    private static final int MAX_REPO_PAGES = 10;

    public List<Map<String, Object>> listUserRepos(String accessToken) {
        List<Map<String, Object>> all = new ArrayList<>();
        int page = 1;
        while (page <= MAX_REPO_PAGES) {
            final int currentPage = page;
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
            if (page == MAX_REPO_PAGES) {
                log.warn("GitHub repo list may be truncated at {} repos (hit the {}-page cap)",
                        all.size(), MAX_REPO_PAGES);
            }
            page++;
        }
        return all;
    }

    public Map<String, Object> getRepoTree(String accessToken, String owner, String repo, String branch) {
        return client(accessToken)
                .get()
                .uri("/repos/{owner}/{repo}/git/trees/{branch}?recursive=1", owner, repo, branch)
                .retrieve()
                .body(MAP);
    }

    public String getFileContent(String accessToken, String owner, String repo, String path) {
        String encodedOwner = UriUtils.encodePathSegment(owner, StandardCharsets.UTF_8);
        String encodedRepo = UriUtils.encodePathSegment(repo, StandardCharsets.UTF_8);
        String encodedPath = Arrays.stream(path.split("/", -1))
                .map(segment -> UriUtils.encodePathSegment(segment, StandardCharsets.UTF_8))
                .collect(Collectors.joining("/"));

        Map<String, Object> body = client(accessToken)
                .get()
                .uri(URI.create(API_BASE + "/repos/" + encodedOwner + "/" + encodedRepo + "/contents/" + encodedPath))
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
    }

    private RestClient client(String accessToken){
        // restClientBuilder is a shared singleton bean; clone it before mutating so concurrent
        // calls from different indexing threads don't stomp on each other's baseUrl/headers.
        return restClientBuilder
                .clone()
                .baseUrl(API_BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "DevPilot")
                .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    throw translateError(response.getStatusCode().value());
                })
                .build();
    }

    /**
     * Centralizes GitHub error translation for every call this client makes, so no caller ever
     * has to handle a raw HttpClientErrorException/HttpServerErrorException (whose message
     * embeds GitHub's literal JSON error body) itself.
     */
    private static ExternalServiceException translateError(int status) {
        String message = switch (status) {
            case 401 -> "Your GitHub connection has expired. Please log out and reconnect your GitHub account.";
            case 403 -> "GitHub denied this request — you may have hit a rate limit, or lost access to this repository.";
            case 404 -> "The repository or branch was not found on GitHub. It may have been renamed, deleted, or made private.";
            default -> status >= 500
                    ? "GitHub is currently unavailable. Please try again in a few minutes."
                    : "GitHub request failed (status " + status + ").";
        };
        return new ExternalServiceException("GitHub", status, message);
    }
}