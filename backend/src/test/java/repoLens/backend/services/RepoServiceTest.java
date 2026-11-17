package repoLens.backend.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import repoLens.backend.dto.RepositoryResponse;
import repoLens.backend.entity.Repository;
import repoLens.backend.entity.User;
import repoLens.backend.repository.RepositoryRepository;
import repoLens.backend.services.github.GithubApiClient;

@ExtendWith(MockitoExtension.class)
class RepoServiceTest {

    @Mock
    private RepositoryRepository repositoryRepository;

    @Mock
    private UserService userService;

    @Mock
    private GithubApiClient gitHubApiClient;

    @InjectMocks
    private RepoService repoService;

    @Test
    void syncAndListReposDeletesRepositoriesMissingFromGitHub() {
        UUID userId = UUID.randomUUID();
        Repository staleRepo = Repository.builder()
                .id(UUID.randomUUID())
                .userId(userId)
                .githubRepoId(10L)
                .fullName("owner/deleted")
                .build();
        User user = User.builder().build();
        Map<String, Object> currentRemoteRepo = Map.of(
                "id", 20L,
                "full_name", "owner/current",
                "private", false,
                "default_branch", "main");

        when(userService.requiredById(userId)).thenReturn(user);
        when(userService.decryptAccessToken(user)).thenReturn("token");
        when(gitHubApiClient.listUserRepos("token")).thenReturn(List.of(currentRemoteRepo));
        when(repositoryRepository.findByUserIdOrderByFullNameAsc(userId)).thenReturn(List.of(staleRepo));
        when(repositoryRepository.findByUserIdAndGithubRepoId(userId, 20L)).thenReturn(Optional.empty());
        when(repositoryRepository.save(any(Repository.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        List<RepositoryResponse> result = repoService.syncAndListRepos(userId);

        assertEquals(1, result.size());
        assertEquals("owner/current", result.get(0).fullName());
        verify(repositoryRepository).deleteAll(List.of(staleRepo));
    }
}
