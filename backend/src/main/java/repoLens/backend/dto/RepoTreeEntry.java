package repoLens.backend.dto;

public record RepoTreeEntry(String path, String type, Long size, boolean eligible) {
}
