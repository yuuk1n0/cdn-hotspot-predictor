package com.yukino.cdn.top50;

import java.time.LocalDate;
import java.util.List;

public record Top50Options(
        String dataSource,
        String dataNotice,
        LocalDate minDate,
        LocalDate maxDate,
        List<String> appIds,
        List<FilterOption> granularities,
        List<String> modelVersions,
        List<FilterOption> compressionStatuses
) {
    public record FilterOption(String value, String label) {
    }
}
