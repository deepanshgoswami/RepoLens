package repoLens.backend.controllers;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import repoLens.backend.dto.IndexRepositoryRequest;
import repoLens.backend.dto.IndexStatusResponse;
import repoLens.backend.dto.RepoTreeEntry;
import repoLens.backend.dto.RepositoryResponse;
import repoLens.backend.entity.Repository;
import repoLens.backend.security.CurrentUser;
import repoLens.backend.services.RepoService;
import repoLens.backend.services.indexing.IndexingService;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/repos")
@RequiredArgsConstructor
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

    @GetMapping("/{id}/tree")
    public List<RepoTreeEntry> tree(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        repoService.requireOwned(id, userId);
        return indexingService.listSelectableFiles(id, userId);
    }

    @PostMapping("/{id}/index")
    public ResponseEntity<RepositoryResponse> index(
            @PathVariable UUID id,
            @RequestBody(required = false) IndexRepositoryRequest request) {
        UUID userId = currentUser.require().getId();
        List<String> selectedPaths = request == null ? List.of() : request.paths();
        Repository repo = indexingService.startIndexing(id, userId, selectedPaths);
        indexingService.indexAsync(id, userId, selectedPaths);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(repoService.toResponse(repo));
    }

    @GetMapping("/{id}/status")
    public IndexStatusResponse status(@PathVariable UUID id) {
        UUID userId = currentUser.require().getId();
        return repoService.status(id, userId);
    }

}