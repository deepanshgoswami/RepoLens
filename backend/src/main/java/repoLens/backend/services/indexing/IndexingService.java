package repoLens.backend.services.indexing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import repoLens.backend.dto.RepoTreeEntry;
import repoLens.backend.entity.IndexStatus;
import repoLens.backend.entity.Repository;
import repoLens.backend.exceptions.BadRequestException;
import repoLens.backend.exceptions.NotFoundException;
import repoLens.backend.repository.RepositoryRepository;
import repoLens.backend.services.UserService;
import repoLens.backend.services.ai.RagSettings;
import repoLens.backend.services.github.GitHubRateLimiter;
import repoLens.backend.services.github.GithubApiClient;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexingService {
    private static final int VECTOR_BATCH_SIZE = 32;
    private static final int PROGRESS_EVERY_N_FILES = 5;

    private final RepositoryRepository repositoryRepository;
    private final UserService userService;
    private final GithubApiClient gitHubApiClient;
    private final CodeFileFilter fileFilter;
    private final CodeChunker codeChunker;
    private final GitHubRateLimiter rateLimiter;
    private final VectorStore vectorStore;

    @Value("${app.indexing.max-file-bytes:102400}")
    private long maxFileBytes;

    @Value("${app.indexing.max-selected-files:150}")
    private int maxSelectedFiles;

    @EventListener(ApplicationReadyEvent.class)
    public void recoverStaleIndexing() {
        repositoryRepository.findByIndexStatus(IndexStatus.INDEXING)
                .forEach(repo -> {
                    repo.setIndexStatus(IndexStatus.FAILED);
                    repo.setErrorMessage("Previous indexing job was interrupted. Please retry indexing.");
                    repo.setUpdatedAt(Instant.now());
                    repositoryRepository.save(repo);
                    log.warn("Recovered stale indexing status for repo {} ({})",
                            repo.getFullName(), repo.getId());
                });
    }

    public List<RepoTreeEntry> listSelectableFiles(UUID repoId, UUID userId) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));
        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());
        return buildRepoTreeEntries(tree);
    }

    public Repository startIndexing(UUID repoId, UUID userId) {
        return startIndexing(repoId, userId, List.of());
    }

    public Repository startIndexing(UUID repoId, UUID userId, List<String> selectedPaths) {
        Repository repo = repositoryRepository.findByIdAndUserId(repoId, userId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));

        if (repo.getIndexStatus() == IndexStatus.INDEXING) {
            throw new BadRequestException("Repository is already being indexed");
        }

        List<String> filePaths = resolveSelectedPaths(repo, userId, selectedPaths);
        if (filePaths.size() > maxSelectedFiles) {
            throw new BadRequestException(
                    "This repo has " + filePaths.size() + " eligible files. Please select at most "
                            + maxSelectedFiles + " files for free-tier indexing.");
        }

        repo.setIndexStatus(IndexStatus.INDEXING);
        repo.setFilesProcessed(0);
        repo.setFilesTotal(0);
        repo.setChunkCount(0);
        repo.setErrorMessage(null);
        repo.setUpdatedAt(Instant.now());
        return repositoryRepository.save(repo);
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId) {
        indexAsync(repoId, userId, List.of());
    }

    @Async("indexingExecutor")
    public void indexAsync(UUID repoId, UUID userId, List<String> selectedPaths) {
        try {
            doIndex(repoId, userId, selectedPaths);
        } catch (Exception ex) {
            log.error("Indexing failed for repo {}", repoId, ex);
            markFailed(repoId, ex.getMessage());
        }
    }

    private void doIndex(UUID repoId, UUID userId, List<String> selectedPaths) {
        Repository repo = repositoryRepository.findById(repoId)
                .orElseThrow(() -> new NotFoundException("Repository not found"));
        String token = userService.decryptAccessToken(userService.requiredById(userId));

        deleteExistingVectors(repoId.toString());

        List<String> filePaths = resolveSelectedPaths(repo, userId, selectedPaths);
        if (filePaths.size() > maxSelectedFiles) {
            throw new BadRequestException(
                    "This repo has " + filePaths.size() + " eligible files. Please select at most "
                            + maxSelectedFiles + " files for free-tier indexing.");
        }

        updateProgress(repoId, filePaths.size(), 0, 0, IndexStatus.INDEXING, null);

        List<Document> batch = new ArrayList<>();
        int processed = 0;
        int totalChunks = 0;

        for (String path : filePaths) {
            try {
                String content = gitHubApiClient.getFileContent(
                        token, repo.getOwner(), repo.getName(), path);
                List<Document> chunks = codeChunker.chunkFile(repoId.toString(), path, content);
                batch.addAll(chunks);
                totalChunks += chunks.size();
                if (batch.size() >= VECTOR_BATCH_SIZE) {
                    vectorStore.add(batch);
                    batch.clear();
                }
            } catch (Exception ex) {
                log.warn("Skipping file {} in {}: {}", path, repo.getFullName(), ex.getMessage());
            }

            processed++;
            if (processed % PROGRESS_EVERY_N_FILES == 0 || processed == filePaths.size()) {
                updateProgress(repoId, filePaths.size(), processed, totalChunks, IndexStatus.INDEXING, null);
            }
            rateLimiter.pause();
        }

        if (!batch.isEmpty()) {
            vectorStore.add(batch);
        }

        markReady(repoId, filePaths.size(), processed, totalChunks, repo.getFullName());
    }


    @SuppressWarnings("unchecked")
    private List<RepoTreeEntry> buildRepoTreeEntries(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .map(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    boolean eligible = fileFilter.isEligible(path, size, maxFileBytes);
                    return new RepoTreeEntry(path, "file", size, eligible);
                })
                .toList();
    }

    private List<String> resolveSelectedPaths(Repository repo, UUID userId, List<String> selectedPaths) {
        String token = userService.decryptAccessToken(userService.requiredById(userId));
        Map<String, Object> tree = gitHubApiClient.getRepoTree(
                token, repo.getOwner(), repo.getName(), repo.getDefaultBranch());

        if (selectedPaths == null || selectedPaths.isEmpty()) {
            return listIndexableFiles(tree);
        }

        return resolveSelectedFiles(tree, selectedPaths);
    }

    @SuppressWarnings("unchecked")
    private List<String> resolveSelectedFiles(Map<String, Object> tree, List<String> selectedPaths) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        Set<String> validPaths = new LinkedHashSet<>();
        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        for (Map<String, Object> entry : entries) {
            if (!"blob".equals(String.valueOf(entry.get("type")))) {
                continue;
            }
            String path = String.valueOf(entry.get("path"));
            long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
            if (fileFilter.isEligible(path, size, maxFileBytes)) {
                validPaths.add(path);
            }
        }

        return selectedPaths.stream()
                .map(path -> path.replace('\\', '/'))
                .filter(validPaths::contains)
                .distinct()
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<String> listIndexableFiles(Map<String, Object> tree) {
        if (tree == null || tree.get("tree") == null) {
            return List.of();
        }

        List<Map<String, Object>> entries = (List<Map<String, Object>>) tree.get("tree");
        return entries.stream()
                .filter(entry -> "blob".equals(String.valueOf(entry.get("type"))))
                .filter(entry -> {
                    String path = String.valueOf(entry.get("path"));
                    long size = entry.get("size") instanceof Number n ? n.longValue() : 0L;
                    return fileFilter.isEligible(path, size, maxFileBytes);
                })
                .map(entry -> String.valueOf(entry.get("path")))
                .toList();
    }

    private void deleteExistingVectors(String repoId) {
        try {
            var filter = new FilterExpressionBuilder().eq(RagSettings.METADATA_REPO_ID, repoId).build();
            vectorStore.delete(filter);
        } catch (Exception ex) {
            log.warn("Could not delete existing vectors for repo {}: {}", repoId, ex.getMessage());
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
    protected void markReady(UUID repoId, int totalFiles, int processedFiles, int totalChunks, String fullName) {
        repositoryRepository.findById(repoId).ifPresent(repo -> {
            repo.setIndexStatus(IndexStatus.READY);
            repo.setFilesTotal(totalFiles);
            repo.setFilesProcessed(processedFiles);
            repo.setChunkCount(totalChunks);
            repo.setIndexedAt(Instant.now());
            repo.setErrorMessage(null);
            repo.setUpdatedAt(Instant.now());
            repositoryRepository.save(repo);
        });
        log.info("Indexed {} files ({} chunks) for {}", processedFiles, totalChunks, fullName);
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