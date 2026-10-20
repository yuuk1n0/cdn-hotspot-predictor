package com.yukino.cdn.top50;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.Date;
import java.util.List;

@Repository
public class Top50Repository {

    private static final String SELECT_COLUMNS = """
            SELECT prediction_date, app_id, granularity, rank_no, file_key, file_type,
                   file_size_bytes, predicted_bytes, prediction_score, recommendation_reason,
                   recent_actual_bytes, compression_status, model_version
            FROM top50_prediction
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public Top50Repository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public long count(Top50Query query) {
        SqlParts sqlParts = buildWhere(query);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM top50_prediction" + sqlParts.whereClause(),
                sqlParts.parameters(),
                Long.class
        );
        return count == null ? 0L : count;
    }

    public List<Top50Item> findAll(Top50Query query) {
        SqlParts sqlParts = buildWhere(query);
        sqlParts.parameters()
                .addValue("limit", query.pageSize())
                .addValue("offset", (query.page() - 1) * query.pageSize());

        return jdbcTemplate.query(
                buildListSql(sqlParts.whereClause()),
                sqlParts.parameters(),
                (resultSet, rowNumber) -> new Top50Item(
                        resultSet.getDate("prediction_date").toLocalDate(),
                        resultSet.getString("app_id"),
                        resultSet.getString("granularity"),
                        resultSet.getInt("rank_no"),
                        resultSet.getString("file_key"),
                        resultSet.getString("file_type"),
                        resultSet.getBigDecimal("file_size_bytes").toPlainString(),
                        resultSet.getBigDecimal("predicted_bytes").toPlainString(),
                        resultSet.getBigDecimal("prediction_score"),
                        resultSet.getString("recommendation_reason"),
                        resultSet.getBigDecimal("recent_actual_bytes").toPlainString(),
                        resultSet.getString("compression_status"),
                        resultSet.getString("model_version")
                )
        );
    }

    String buildListSql(Top50Query query) {
        return buildListSql(buildWhere(query).whereClause());
    }

    public Top50Options loadOptions() {
        DateRange dateRange = jdbcTemplate.queryForObject(
                "SELECT MIN(prediction_date) AS min_date, MAX(prediction_date) AS max_date FROM top50_prediction",
                new MapSqlParameterSource(),
                (resultSet, rowNumber) -> new DateRange(
                        toLocalDate(resultSet.getDate("min_date")),
                        toLocalDate(resultSet.getDate("max_date"))
                )
        );
        List<String> appIds = queryDistinct("app_id");
        List<String> modelVersions = queryDistinct("model_version");
        List<Top50Options.FilterOption> granularities = queryDistinct("granularity").stream()
                .map(value -> new Top50Options.FilterOption(value, granularityLabel(value)))
                .toList();
        List<Top50Options.FilterOption> compressionStatuses = queryDistinct("compression_status").stream()
                .map(value -> new Top50Options.FilterOption(value, compressionStatusLabel(value)))
                .toList();

        return new Top50Options(
                "FAKE",
                Top50Service.DATA_NOTICE,
                dateRange == null ? null : dateRange.minDate(),
                dateRange == null ? null : dateRange.maxDate(),
                appIds,
                granularities,
                modelVersions,
                compressionStatuses
        );
    }

    private List<String> queryDistinct(String column) {
        return jdbcTemplate.getJdbcTemplate().queryForList(
                "SELECT DISTINCT " + column + " FROM top50_prediction ORDER BY " + column,
                String.class
        );
    }

    private SqlParts buildWhere(Top50Query query) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource parameters = new MapSqlParameterSource();

        if (query.startDate() != null) {
            where.append(" AND prediction_date >= :startDate");
            parameters.addValue("startDate", query.startDate());
        }
        if (query.endDate() != null) {
            where.append(" AND prediction_date <= :endDate");
            parameters.addValue("endDate", query.endDate());
        }
        addTextFilter(where, parameters, "app_id", "appId", query.appId());
        addTextFilter(where, parameters, "granularity", "granularity", query.granularity());
        addTextFilter(where, parameters, "model_version", "modelVersion", query.modelVersion());
        addTextFilter(where, parameters, "compression_status", "compressionStatus", query.compressionStatus());

        return new SqlParts(where.toString(), parameters);
    }

    private String buildListSql(String whereClause) {
        return SELECT_COLUMNS + whereClause + """
                 ORDER BY prediction_date DESC, app_id, granularity, model_version, rank_no
                LIMIT :limit OFFSET :offset
                """;
    }

    private void addTextFilter(
            StringBuilder where,
            MapSqlParameterSource parameters,
            String column,
            String parameter,
            String value
    ) {
        if (StringUtils.hasText(value)) {
            where.append(" AND ").append(column).append(" = :").append(parameter);
            parameters.addValue(parameter, value);
        }
    }

    private java.time.LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private String granularityLabel(String value) {
        return "WEEKLY".equals(value) ? "周" : "日";
    }

    private String compressionStatusLabel(String value) {
        return switch (value) {
            case "PROCESSING" -> "处理中";
            case "COMPRESSED" -> "已压缩";
            default -> "待处理";
        };
    }

    private record SqlParts(String whereClause, MapSqlParameterSource parameters) {
    }

    private record DateRange(java.time.LocalDate minDate, java.time.LocalDate maxDate) {
    }
}
