package repoLens.backend.dto;

import java.util.List;

public record IndexRepositoryRequest(List<String> paths) {
    public List<String> paths() {
        return paths == null ? List.of() : paths;
    }
}
