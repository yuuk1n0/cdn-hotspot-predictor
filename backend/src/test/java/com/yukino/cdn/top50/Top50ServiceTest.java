package com.yukino.cdn.top50;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Top50ServiceTest {

    @Test
    void returnsPagedFakeDataWithoutChangingTheQuery() {
        RecordingRepository repository = new RecordingRepository(12L);
        Top50Query query = new Top50Query(
                LocalDate.of(2026, 10, 18),
                LocalDate.of(2026, 10, 20),
                "10066",
                "DAILY",
                "last_day_v1",
                "PENDING",
                2,
                10
        );
        Top50Page result = new Top50Service(repository).search(query);

        assertEquals("FAKE", result.dataSource());
        assertEquals(2, result.page());
        assertEquals(10, result.pageSize());
        assertEquals(12L, result.total());
        assertEquals(query, repository.countQuery);
        assertEquals(query, repository.findQuery);
    }

    @Test
    void rejectsAnEndDateBeforeTheStartDate() {
        RecordingRepository repository = new RecordingRepository(0L);
        Top50Query query = new Top50Query(
                LocalDate.of(2026, 10, 20),
                LocalDate.of(2026, 10, 18),
                null,
                null,
                null,
                null,
                1,
                10
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Top50Service(repository).search(query)
        );

        assertEquals("endDate 不能早于 startDate", exception.getMessage());
        assertEquals(0, repository.invocationCount);
    }

    @Test
    void rejectsAnUnsupportedGranularity() {
        RecordingRepository repository = new RecordingRepository(0L);
        Top50Query query = new Top50Query(
                null,
                null,
                null,
                "MONTHLY",
                null,
                null,
                1,
                10
        );

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new Top50Service(repository).search(query)
        );

        assertEquals("granularity 仅支持 DAILY 或 WEEKLY", exception.getMessage());
        assertEquals(0, repository.invocationCount);
    }

    private static final class RecordingRepository extends Top50Repository {

        private final long total;
        private int invocationCount;
        private Top50Query countQuery;
        private Top50Query findQuery;

        private RecordingRepository(long total) {
            super(null);
            this.total = total;
        }

        @Override
        public long count(Top50Query query) {
            invocationCount++;
            countQuery = query;
            return total;
        }

        @Override
        public List<Top50Item> findAll(Top50Query query) {
            invocationCount++;
            findQuery = query;
            return List.of();
        }
    }
}
