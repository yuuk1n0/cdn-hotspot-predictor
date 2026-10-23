package com.yukino.cdn.top50;

import java.util.List;

public record Top50Page(
        String dataSource,
        String dataNotice,
        int page,
        int pageSize,
        long total,
        List<Top50Item> items
) {
}
