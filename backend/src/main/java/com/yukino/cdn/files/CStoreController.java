package com.yukino.cdn.files;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import static com.yukino.cdn.files.CStoreSettings.problem;

@RestController
@RequestMapping("/api/v1/files")
public class CStoreController {
    private final CStoreFileService service;
    public CStoreController(CStoreFileService service) { this.service = service; }
    @PostMapping("/metadata")
    public ResponseEntity<?> metadata(@RequestBody JsonNode body) {
        if (!body.path("fileKeys").isArray() || body.path("fileKeys").size() > 50) throw problem(422, "INVALID_FILE_REQUEST", "fileKeys须为1至50个字符串。");
        var keys = new ArrayList<String>(); body.path("fileKeys").forEach(key -> { if (!key.isTextual()) throw problem(422, "INVALID_FILE_REQUEST", "fileKey须为字符串。"); keys.add(key.textValue()); });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.metadata(text(body, "runId"), keys));
    }
    @PostMapping("/access-url")
    public ResponseEntity<?> access(@RequestBody JsonNode body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.access(text(body, "runId"), text(body, "fileKey"), text(body, "purpose")));
    }
    private static String text(JsonNode body, String key) { if (!body.path(key).isTextual()) throw problem(422, "INVALID_FILE_REQUEST", key + "须为字符串。"); return body.path(key).textValue(); }
}
