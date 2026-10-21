package com.yukino.cdn.files;

import com.fasterxml.jackson.databind.JsonNode;
import com.yukino.cdn.prediction.PredictionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static com.yukino.cdn.files.CStoreSettings.problem;

@Service
public final class CStoreFileService {
    private final Function<String, JsonNode> snapshots;
    private final CStoreSettings settings;
    private final CStoreClient client;
    @Autowired
    public CStoreFileService(PredictionService predictions, CStoreSettings settings, CStoreClient client) { this(predictions::result, settings, client); }
    CStoreFileService(Function<String, JsonNode> snapshots, CStoreSettings settings, CStoreClient client) { this.snapshots = snapshots; this.settings = settings; this.client = client; }
    private Context context(String runId, List<String> keys) {
        if (runId == null || runId.isBlank() || runId.length() > 128 || keys == null || keys.isEmpty() || keys.size() > 50 || new HashSet<>(keys).size() != keys.size()
                || keys.stream().anyMatch(key -> key == null || key.isBlank() || key.length() > 4096 || key.indexOf('\0') >= 0))
            throw problem(422, "INVALID_FILE_REQUEST", "须提供已发布runId及1至50个不同的原始文件标识。");
        JsonNode snapshot = snapshots.apply(runId);
        if (!snapshot.path("runId").asText().equals(runId) || !snapshot.path("items").isArray() || !snapshot.path("appId").isTextual())
            throw problem(422, "FILE_CONTEXT_MISMATCH", "运行快照身份不一致。");
        if (!snapshot.path("datasetSource").asText().equals("REAL")) throw problem(422, "FILE_SOURCE_NOT_REAL", "FAKE/UI样例文件不用于真实CStore查询。");
        Set<String> available = new HashSet<>(); snapshot.path("items").forEach(item -> available.add(item.path("fileKey").asText()));
        if (!available.containsAll(keys)) throw problem(422, "FILE_CONTEXT_MISMATCH", "请求文件不属于当前业务运行，未调用CStore。");
        settings.requireConfigured();
        String appId = snapshot.path("appId").textValue(); var mapping = settings.mapping(appId);
        Map<String, String> mapped = new LinkedHashMap<>();
        keys.forEach(key -> mapped.put(key, mapping.fileKey(key)));
        return new Context(appId, mapping, mapped);
    }
    public MetadataBatch metadata(String runId, List<String> keys) {
        Context context = context(runId, keys);
        List<String> storageKeys = new ArrayList<>(new LinkedHashSet<>(context.keys.values()));
        Map<String, CStoreClient.Meta> values = new HashMap<>();
        for (int offset = 0; offset < storageKeys.size(); offset += 10) {
            var batch = storageKeys.subList(offset, Math.min(offset + 10, storageKeys.size()));
            values.putAll(client.metadata(context.mapping, batch));
        }
        String now = Instant.now().toString(); List<FileMetadata> items = new ArrayList<>();
        for (var key : keys) {
            var meta = values.get(context.keys.get(key));
            items.add(new FileMetadata(runId, context.appId, key, meta.bytes(), meta.contentType(), now, settings.dataSource, meta.errorCode() == null ? "READY" : "UNAVAILABLE", meta.errorCode()));
        }
        return new MetadataBatch(runId, context.appId, items);
    }
    public FileAccess access(String runId, String key, String purpose) {
        if (!Set.of("PREVIEW", "DOWNLOAD").contains(purpose == null ? "" : purpose)) throw problem(422, "INVALID_FILE_REQUEST", "purpose须为PREVIEW或DOWNLOAD。");
        Context context = context(runId, Collections.singletonList(key));
        String url = client.access(context.mapping, context.keys.get(key));
        return new FileAccess(runId, context.appId, key, purpose, url, null, null, Instant.now().toString(), settings.dataSource);
    }
    private record Context(String appId, CStoreSettings.Mapping mapping, Map<String, String> keys) { }
    public record MetadataBatch(String runId, String appId, List<FileMetadata> items) { }
    public record FileMetadata(String runId, String appId, String fileKey, String fileSizeBytes, String contentType, String queriedAt, String dataSource, String status, String errorCode) { }
    public record FileAccess(String runId, String appId, String fileKey, String purpose, String url, String expiresAt, String contentType, String queriedAt, String dataSource) { }
}
