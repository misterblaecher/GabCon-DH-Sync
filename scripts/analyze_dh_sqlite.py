#!/usr/bin/env python3
"""Read-only structural analysis for a Distant Horizons SQLite test archive.

This script intentionally prints schema and aggregate statistics only. It never
prints raw BLOB payloads or arbitrary game data.
"""
from __future__ import annotations

import hashlib
import os
import sqlite3
import sys
import tempfile
import zipfile
from pathlib import Path


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def qident(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def scalar(conn: sqlite3.Connection, sql: str):
    return conn.execute(sql).fetchone()[0]


def analyze_db(db: Path) -> None:
    print(f"\n=== DATABASE {db.name} ===")
    print(f"size_bytes={db.stat().st_size}")
    print(f"sha256={sha256(db)}")

    uri = f"file:{db.as_posix()}?mode=ro"
    conn = sqlite3.connect(uri, uri=True)
    try:
        conn.execute("PRAGMA query_only=ON")
        pragmas = [
            "journal_mode",
            "page_size",
            "page_count",
            "freelist_count",
            "user_version",
            "application_id",
            "schema_version",
            "encoding",
        ]
        for p in pragmas:
            try:
                print(f"pragma.{p}={scalar(conn, f'PRAGMA {p}')}")
            except sqlite3.DatabaseError as e:
                print(f"pragma.{p}=ERROR:{e}")

        try:
            print(f"quick_check={scalar(conn, 'PRAGMA quick_check')}")
        except sqlite3.DatabaseError as e:
            print(f"quick_check=ERROR:{e}")

        objects = conn.execute(
            "SELECT type, name, tbl_name, sql "
            "FROM sqlite_master "
            "WHERE name NOT LIKE 'sqlite_%' "
            "ORDER BY type, name"
        ).fetchall()

        print("\n--- SQLITE OBJECTS ---")
        for typ, name, tbl, sql in objects:
            if sql:
                compact = " ".join(sql.split())
                print(f"{typ}:{name} table={tbl} sql={compact}")
            else:
                print(f"{typ}:{name} table={tbl}")

        tables = [row[1] for row in objects if row[0] == "table"]
        print("\n--- TABLE STATS ---")
        for table in tables:
            qt = qident(table)
            try:
                count = scalar(conn, f"SELECT COUNT(*) FROM {qt}")
            except sqlite3.DatabaseError as e:
                print(f"table={table} ERROR count: {e}")
                continue

            cols = conn.execute(f"PRAGMA table_info({qt})").fetchall()
            print(f"table={table} rows={count}")
            for cid, name, declared_type, notnull, default, pk in cols:
                declared = (declared_type or "").upper()
                qc = qident(name)
                print(
                    f"  col={name} declared={declared_type or ''} "
                    f"notnull={notnull} pk={pk} default={default!r}"
                )
                if count == 0:
                    continue
                try:
                    if any(k in declared for k in ("INT", "REAL", "FLOAT", "DOUBLE", "NUM")):
                        mn, mx = conn.execute(
                            f"SELECT MIN({qc}), MAX({qc}) FROM {qt}"
                        ).fetchone()
                        print(f"    numeric_range=min:{mn} max:{mx}")
                    elif "BLOB" in declared or declared == "":
                        mn, mx, avg = conn.execute(
                            f"SELECT MIN(LENGTH({qc})), MAX(LENGTH({qc})), "
                            f"ROUND(AVG(LENGTH({qc})), 2) FROM {qt} "
                            f"WHERE {qc} IS NOT NULL"
                        ).fetchone()
                        print(f"    length_stats=min:{mn} max:{mx} avg:{avg}")
                    elif any(k in declared for k in ("CHAR", "TEXT", "CLOB")):
                        mn, mx, avg = conn.execute(
                            f"SELECT MIN(LENGTH({qc})), MAX(LENGTH({qc})), "
                            f"ROUND(AVG(LENGTH({qc})), 2) FROM {qt} "
                            f"WHERE {qc} IS NOT NULL"
                        ).fetchone()
                        print(f"    text_length_stats=min:{mn} max:{mx} avg:{avg}")
                except sqlite3.DatabaseError as e:
                    print(f"    stats_error={e}")

            indexes = conn.execute(f"PRAGMA index_list({qt})").fetchall()
            for idx in indexes:
                print(f"  index={idx}")

    finally:
        conn.close()


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: analyze_dh_sqlite.py <zip-or-sqlite>", file=sys.stderr)
        return 2

    source = Path(sys.argv[1])
    if not source.exists():
        print(f"not found: {source}", file=sys.stderr)
        return 2

    if source.suffix.lower() == ".zip":
        print(f"archive={source}")
        print(f"archive_size_bytes={source.stat().st_size}")
        print(f"archive_sha256={sha256(source)}")
        with zipfile.ZipFile(source) as zf, tempfile.TemporaryDirectory(prefix="gabcon-dh-") as td:
            print("\n--- ZIP ENTRIES ---")
            for info in zf.infolist():
                print(
                    f"entry={info.filename} size={info.file_size} "
                    f"compressed={info.compress_size} crc={info.CRC:08x}"
                )
            zf.extractall(td)
            root = Path(td)
            dbs = sorted(
                p for p in root.rglob("*")
                if p.is_file() and (
                    p.suffix.lower() in {".sqlite", ".db"} or
                    "distanthorizons" in p.name.lower()
                ) and not p.name.endswith(("-wal", "-shm"))
            )
            if not dbs:
                print("No SQLite candidates found.", file=sys.stderr)
                return 1
            for db in dbs:
                try:
                    analyze_db(db)
                except sqlite3.DatabaseError as e:
                    print(f"skip_non_sqlite={db.name} error={e}")
    else:
        analyze_db(source)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
