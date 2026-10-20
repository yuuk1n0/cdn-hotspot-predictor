package com.yukino.cdn.top50;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Top50Item(
        LocalDate predictionDate,
        String appId,
        String granularity,
        int rank,
        String fileKey,
        String fileType,
        String fileSizeBytes,
        String predictedBytes,
        BigDecimal score,
        String reason,
        String recentActualBytes,
        String compressionStatus,
        String modelVersion
) {
}
