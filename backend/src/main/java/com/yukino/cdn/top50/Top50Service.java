package com.yukino.cdn.top50;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Set;

@Service
public class Top50Service {

    public static final String DATA_NOTICE = "演示数据，仅用于验证 Top50 查询链路，不代表真实预测结果";

    private static final Set<String> GRANULARITIES = Set.of("DAILY", "WEEKLY");
    private static final Set<String> COMPRESSION_STATUSES = Set.of("PENDING", "PROCESSING", "COMPRESSED");

    private final Top50Repository repository;

    public Top50Service(Top50Repository repository) {
        this.repository = repository;
    }

    public Top50Page search(Top50Query query) {
        validate(query);
        long total = repository.count(query);
        return new Top50Page(
                "FAKE",
                DATA_NOTICE,
                query.page(),
                query.pageSize(),
                total,
                repository.findAll(query)
        );
    }

    public Top50Options options() {
        return repository.loadOptions();
    }

    private void validate(Top50Query query) {
        if (query.startDate() != null && query.endDate() != null && query.endDate().isBefore(query.startDate())) {
            throw new IllegalArgumentException("endDate 不能早于 startDate");
        }
        if (query.page() < 1) {
            throw new IllegalArgumentException("page 必须从 1 开始");
        }
        if (query.pageSize() < 1 || query.pageSize() > 50) {
            throw new IllegalArgumentException("pageSize 必须在 1 到 50 之间");
        }
        if (StringUtils.hasText(query.granularity()) && !GRANULARITIES.contains(query.granularity())) {
            throw new IllegalArgumentException("granularity 仅支持 DAILY 或 WEEKLY");
        }
        if (StringUtils.hasText(query.compressionStatus())
                && !COMPRESSION_STATUSES.contains(query.compressionStatus())) {
            throw new IllegalArgumentException("compressionStatus 不受支持");
        }
    }
}
