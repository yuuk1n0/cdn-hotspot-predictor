package com.yukino.cdn.top50;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class Top50RepositoryTest {

    @Test
    void separatesTheWhereClauseFromTheOrderByClause() {
        Top50Repository repository = new Top50Repository(null);

        String sql = repository.buildListSql(new Top50Query(
                null,
                null,
                null,
                null,
                null,
                null,
                1,
                10
        ));

        assertTrue(sql.contains("WHERE 1 = 1 ORDER BY"));
    }
}
