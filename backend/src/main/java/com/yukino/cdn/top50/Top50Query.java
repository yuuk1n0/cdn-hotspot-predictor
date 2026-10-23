package com.yukino.cdn.top50;

import java.time.LocalDate;

public record Top50Query(
        LocalDate startDate,
        LocalDate endDate,
        String appId,
        String granularity,
        String modelVersion,
        String compressionStatus,
        int page,
        int pageSize
) {
}
