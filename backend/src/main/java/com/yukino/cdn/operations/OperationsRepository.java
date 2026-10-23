package com.yukino.cdn.operations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static com.yukino.cdn.operations.OperationsSupport.*;

@Repository
public class OperationsRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public OperationsRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    ObjectNode read(String value) {
        try { return (ObjectNode) mapper.readTree(value); }
        catch (Exception invalid) { throw problem(503, "OPERATIONS_STORAGE_INVALID", "业务记录无法读取，未返回替代数据。"); }
    }
    public ObjectNode config(String app) {
        var results = jdbc.query("SELECT settings_json,version,updated_at_ms FROM app_config WHERE app_id=:app", Map.of("app", app),
                (rs, i) -> read(rs.getString(1)).put("version", rs.getInt(2)).put("updatedAt", Instant.ofEpochMilli(rs.getLong(3)).toString()));
        return results.isEmpty() ? null : results.get(0);
    }
    public List<String> apps() { return jdbc.query("SELECT app_id FROM app_config ORDER BY app_id", (rs, i) -> rs.getString(1)); }
    public ObjectNode saveConfig(String app, ObjectNode settings, int expected, Instant now) {
        ObjectNode saved = settings.deepCopy().put("appId", app).put("version", expected + 1).put("updatedAt", now.toString());
        var p = new MapSqlParameterSource("app", app).addValue("json", saved.toString()).addValue("version", expected + 1).addValue("expected", expected).addValue("now", now.toEpochMilli());
        try {
            if (expected == 0) jdbc.update("INSERT INTO app_config (app_id,settings_json,version,updated_at_ms) VALUES(:app,:json,:version,:now)", p);
            else if (jdbc.update("UPDATE app_config SET settings_json=:json,version=:version,updated_at_ms=:now WHERE app_id=:app AND version=:expected", p) != 1)
                throw problem(409, "VERSION_CONFLICT", "配置已变化，请刷新后再修改。");
        } catch (DuplicateKeyException conflict) { throw problem(409, "VERSION_CONFLICT", "配置已创建，请刷新后再修改。"); }
        return saved;
    }
    public List<ObjectNode> records(String app, String source) {
        return jdbc.query("SELECT record_json FROM compression_record WHERE app_id=:app AND evidence_source=:source ORDER BY updated_at_ms DESC,id", Map.of("app", app, "source", source), (rs, i) -> read(rs.getString(1)));
    }
    public ObjectNode record(String id) {
        var values = jdbc.query("SELECT record_json FROM compression_record WHERE id=:id", Map.of("id", id), (rs, i) -> read(rs.getString(1)));
        if (values.isEmpty()) throw problem(404, "COMPRESSION_NOT_FOUND", "处理记录不存在。"); return values.get(0);
    }
    public ObjectNode idempotent(String hash, String fingerprint) {
        var values = jdbc.query("SELECT record_json,request_hash FROM compression_record WHERE idempotency_key_hash=:hash", Map.of("hash", hash), (rs, i) -> {
            if (!fingerprint.equals(rs.getString(2))) throw problem(409, "IDEMPOTENCY_CONFLICT", "同一幂等键不能用于不同登记。"); return read(rs.getString(1));
        });
        return values.isEmpty() ? null : values.get(0);
    }
    public ObjectNode insert(ObjectNode record, String keyHash, String requestHash, Instant now) {
        var p = new MapSqlParameterSource("id", record.path("id").asText()).addValue("app", record.path("appId").asText()).addValue("key", record.path("fileKey").asText())
                .addValue("hash", hash(record.path("fileKey").asText())).addValue("source", record.path("evidenceSource").asText()).addValue("status", record.path("status").asText())
                .addValue("json", record.toString()).addValue("idem", keyHash).addValue("request", requestHash).addValue("now", now.toEpochMilli());
        try { jdbc.update("""
                INSERT INTO compression_record(id,app_id,file_key,file_key_hash,evidence_source,status,record_json,version,idempotency_key_hash,request_hash,created_at_ms,updated_at_ms)
                VALUES(:id,:app,:key,:hash,:source,:status,:json,1,:idem,:request,:now,:now)
                """, p); }
        catch (DuplicateKeyException conflict) {
            var prior = idempotent(keyHash, requestHash); if (prior != null) return prior;
            throw problem(409, "ALREADY_REGISTERED", "该App、文件和证据范围已有记录，请更新已有记录。");
        }
        return record;
    }
    public ObjectNode update(ObjectNode record, int expected, Instant now) {
        var saved = record.deepCopy().put("version", expected + 1).put("updatedAt", now.toString());
        int changed = jdbc.update("UPDATE compression_record SET status=:status,record_json=:json,version=:version,updated_at_ms=:now WHERE id=:id AND version=:expected",
                new MapSqlParameterSource("id", saved.path("id").asText()).addValue("status", saved.path("status").asText()).addValue("json", saved.toString())
                        .addValue("version", expected + 1).addValue("expected", expected).addValue("now", now.toEpochMilli()));
        if (changed != 1) throw problem(409, "VERSION_CONFLICT", "处理记录已变化，请刷新。"); return saved;
    }
}
