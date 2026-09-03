package devPilot.backend.services.indexing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import devPilot.backend.services.ai.RagSettings;

@Component
public class CodeChunker {
    private final TokenTextSplitter splitter;
    private final CodeFileFilter fileFilter;


    public CodeChunker(
            @Value("${app.indexing.chunk-size:800}") int chunkSize,
            CodeFileFilter fileFilter) {
        // Spring AI splits by tokens; ~4 characters per token is a reasonable default for code.
        int chunkTokens = Math.max(50, chunkSize / 4);

        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunkTokens)
                .build();
        this.fileFilter = fileFilter;
    }

    public List<Document> chunkFile(String repoId, String filePath, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        String language = fileFilter.detectLanguage(filePath);
        String header = "// File: " + filePath + "\n";
        String fullText = header + content;

        Document source = new Document(fullText, baseMetadata(repoId, filePath, language));
        List<Document> split = splitter.apply(List.of(source));

        List<Document> result = new ArrayList<>(split.size());
        int searchFrom = 0;
        for (int i = 0; i < split.size(); i++) {
            Document chunk = split.get(i);
            int[] range = locateLineRange(fullText, chunk.getText(), searchFrom);
            Integer startLine = range != null ? range[0] : null;
            Integer endLine = range != null ? range[1] : null;
            if (range != null) {
                searchFrom = range[2];
            }
            result.add(withChunkIndex(chunk, repoId, filePath, language, i, startLine, endLine));
        }
        return result;
    }

    /**
     * Best-effort: locates a chunk's text back within the original source to derive its
     * 1-based start/end line range. The splitter may trim whitespace per chunk, so this
     * searches for the trimmed text rather than requiring an exact match. Returns null if the
     * chunk text can't be located (e.g. the splitter altered it beyond a simple trim).
     */
    private static int[] locateLineRange(String fullText, String chunkText, int searchFrom) {
        String probe = chunkText.strip();
        if (probe.isEmpty()) {
            return null;
        }
        int idx = fullText.indexOf(probe, Math.max(0, searchFrom));
        if (idx < 0) {
            idx = fullText.indexOf(probe);
        }
        if (idx < 0) {
            return null;
        }
        int startLine = countNewlines(fullText, 0, idx) + 1;
        int endLine = startLine + countNewlines(fullText, idx, idx + probe.length());
        return new int[] { startLine, endLine, idx + probe.length() };
    }

    private static int countNewlines(String text, int from, int to) {
        int count = 0;
        for (int i = from; i < to; i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Object> baseMetadata(String repoId, String filePath, String language) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put("filePath", filePath);
        metadata.put("language", language);
        return metadata;
    }

    private static Document withChunkIndex(
            Document chunk,
            String repoId,
            String filePath,
            String language,
            int chunkIndex,
            Integer startLine,
            Integer endLine) {
        Map<String, Object> metadata = new HashMap<>(chunk.getMetadata());
        metadata.put(RagSettings.METADATA_REPO_ID, repoId);
        metadata.put("filePath", filePath);
        metadata.put("language", language);
        metadata.put("chunkIndex", chunkIndex);
        if (startLine != null) {
            metadata.put("startLine", startLine);
        }
        if (endLine != null) {
            metadata.put("endLine", endLine);
        }
        return new Document(chunk.getText(), metadata);
    }
}