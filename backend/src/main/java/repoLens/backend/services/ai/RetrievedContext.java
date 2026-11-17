package repoLens.backend.services.ai;

import java.util.List;

import repoLens.backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {
}