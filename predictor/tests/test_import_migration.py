import re
from pathlib import Path


MIGRATION = (
    Path(__file__).resolve().parents[2]
    / "backend/src/main/resources/db/migrations/006_imported_observations.sql"
)


def migration_sql() -> str:
    return MIGRATION.read_text(encoding="utf-8")


def compact(sql: str) -> str:
    return re.sub(r"\s+", " ", sql).lower()


def test_006_is_additive_and_defines_the_four_t03_tables():
    sql = compact(migration_sql())

    assert sql.count("create table ") == 4
    for table in (
        "data_import_task",
        "imported_dataset",
        "file_daily_observation",
        "app_daily_total",
    ):
        assert f"create table {table}" in sql

    assert re.search(r"(?:^|;)\s*(drop|truncate|delete|alter)\b", sql) is None


def test_006_versions_rows_by_dataset_and_restricts_dataset_deletion():
    sql = compact(migration_sql())

    assert "unique key uk_file_daily_identity (dataset_id, app_id, observed_date, file_key_hash)" in sql
    assert "unique key uk_app_daily_total_identity (dataset_id, app_id, observed_date)" in sql
    assert sql.count("foreign key (dataset_id) references imported_dataset (dataset_id) on delete restrict") == 2
    assert "dataset_source" not in table_body(sql, "file_daily_observation")
    assert "dataset_source" not in table_body(sql, "app_daily_total")


def test_006_preserves_import_audit_and_decimal_precision():
    sql = compact(migration_sql())
    task = table_body(sql, "data_import_task")
    dataset = table_body(sql, "imported_dataset")
    total = table_body(sql, "app_daily_total")

    for column in (
        "processed_rows",
        "total_rows",
        "valid_rows",
        "invalid_rows",
        "duplicate_rows",
        "aggregated_rows",
        "quality_report_json",
        "error_code",
        "error_message",
    ):
        assert column in task

    assert "status varchar(16) not null" in dataset
    assert "app_id varchar(32)" in dataset
    assert "raw_traffic_value decimal(38,18) not null" in total
    assert "total_traffic_bytes decimal(65,9) not null" in total
    assert "source_unit varchar(16) not null" in total
    assert "source_url" not in sql


def table_body(sql: str, table: str) -> str:
    match = re.search(rf"create table {table}\s*\((.*?)\) engine", sql)
    assert match is not None, f"missing table {table}"
    return match.group(1)
