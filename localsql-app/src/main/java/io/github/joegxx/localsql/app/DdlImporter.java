package io.github.joegxx.localsql.app;

import io.github.joegxx.localsql.duckdb.DuckDbExecutor;
import io.github.joegxx.localsql.thrift.ThriftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports a directory of Hive/Spark CREATE TABLE DDL files into the runtime
 * through the full pipeline (parse - analyze - generate - execute), so both
 * the physical DuckDB tables and the catalog metadata are registered.
 *
 * <p>Target resolution: the CREATE statement's own qualified identifier wins;
 * a bare (single-part) name goes into {@code defaultDatabase}. A manifest CSV
 * (columns: table_name, db_name, file_path, status) overrides the target and
 * filters to status=success entries.
 *
 * <p>Preprocessing per file: CREATE EXTERNAL TABLE is downgraded to a plain
 * CREATE TABLE, IF NOT EXISTS is injected for idempotent re-runs, and
 * varchar(n)/char(n) become string (the Spark 3.2 grammar has no VARCHAR).
 * Statement-level failures are isolated: the partial table is dropped, the
 * error recorded, and the import continues.
 */
public final class DdlImporter {

    private record Entry(String canonical, Path file) {}

    public record Stats(int total, int ok, int failed, List<String> failures) {}

    private static final Pattern CREATE_TARGET = Pattern.compile(
            "(?is)CREATE\\s+(?:EXTERNAL\\s+)?TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([`\"A-Za-z0-9_.]+)");

    public static Stats importAll(ThriftServer server, DuckDbExecutor executor,
                                  Path ddlDir, String defaultDatabase,
                                  Path manifest, Path failuresOut) throws Exception {
        List<Entry> entries = collect(ddlDir, defaultDatabase, manifest);
        System.out.println("import targets: " + entries.size());

        Set<String> databases = new LinkedHashSet<>();
        for (Entry e : entries) databases.add(e.canonical().substring(0, e.canonical().indexOf('.')));
        for (String db : databases) {
            try {
                server.executeSparkSql("CREATE DATABASE IF NOT EXISTS " + db);
            } catch (Exception ex) {
                System.out.println("CREATE DATABASE failed: " + db + " : " + oneLine(ex.getMessage()));
            }
        }

        List<String> failures = new ArrayList<>();
        long start = System.currentTimeMillis();
        int ok = 0;
        // One pre-import snapshot of the physical table set, plus an in-memory
        // set of names this run created (cheaper than re-scanning before every
        // entry). Cleanup consults both so it only ever drops a physical table
        // this import brought into existence, never a pre-existing table with
        // the same bare name in any schema. Without a snapshot
        // (physicalTables returned null) the cleanup falls back to the
        // qualified catalog-level DROP only.
        Set<String> existing = physicalTables(executor);
        Set<String> createdHere = new LinkedHashSet<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            String physical = tableOf(e.canonical());
            String schema = physicalSchema(e.canonical());
            String physicalKey = (schema + "." + physical).toLowerCase(java.util.Locale.ROOT);
            try {
                server.executeSparkSql(preprocess(Files.readString(e.file(), StandardCharsets.UTF_8), e.canonical()));
                ok++;
                createdHere.add(physicalKey);
            } catch (Exception ex) {
                try { server.executeSparkSql("DROP TABLE IF EXISTS " + e.canonical()); } catch (Exception ignore) {}
                if (existing != null && !existing.contains(physicalKey) && !createdHere.contains(physicalKey)) {
                    try { executor.run("DROP TABLE IF EXISTS " + physicalRef(schema, physical)); } catch (Exception ignore) {}
                }
                failures.add(e.canonical() + "\n  " + oneLine(ex.getMessage()) + "\n  [" + e.file() + "]");
            }
            if ((i + 1) % 250 == 0) {
                System.out.println("  " + (i + 1) + "/" + entries.size() + " done, ok=" + ok
                        + " failed=" + failures.size()
                        + " (" + (System.currentTimeMillis() - start) / 1000 + "s)");
            }
        }
        long secs = (System.currentTimeMillis() - start) / 1000;
        System.out.println("import finished: ok=" + ok + " failed=" + failures.size() + " in " + secs + "s");
        if (failuresOut != null && !failures.isEmpty()) {
            Files.writeString(failuresOut, String.join("\n", failures) + "\n");
            System.out.println("failure details: " + failuresOut);
        }
        return new Stats(entries.size(), ok, failures.size(), failures);
    }

    private static List<Entry> collect(Path ddlDir, String defaultDatabase, Path manifest) throws IOException {
        Map<String, Entry> byKey = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        if (manifest != null && Files.exists(manifest)) {
            for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
                if (line.startsWith("table_name") || line.isBlank()) continue;
                String[] f = line.split(",", 6);
                if (f.length < 6 || !"success".equalsIgnoreCase(f[5].trim())) continue;
                String table = f[0].trim();
                String db = f[1].trim();
                String canonical = table.contains(".") ? table : db + "." + table;
                Path p = Paths.get(f[4].trim());
                if (!Files.exists(p)) { skipped.add(canonical + " -> " + p); continue; }
                byKey.putIfAbsent(canonical.toLowerCase(), new Entry(canonical, p));
            }
        } else if (ddlDir != null && Files.isDirectory(ddlDir)) {
            try (var walk = Files.walk(ddlDir)) {
                walk.filter(p -> p.toString().endsWith(".sql")).forEach(p -> {
                    String canonical = targetOf(p, defaultDatabase);
                    if (canonical == null) { skipped.add("no CREATE TABLE target: " + p); return; }
                    byKey.putIfAbsent(canonical.toLowerCase(), new Entry(canonical, p));
                });
            }
        } else {
            throw new IOException("no DDL source: pass --manifest <csv> or --import-ddl <dir>");
        }
        if (!skipped.isEmpty()) {
            System.out.println("skipped " + skipped.size() + " entries: " + skipped.subList(0, Math.min(3, skipped.size())));
        }
        return new ArrayList<>(byKey.values());
    }

    /** Resolves the CREATE target from the file's own statement, falling back to defaultDatabase. */
    private static String targetOf(Path file, String defaultDatabase) {
        try {
            String sql = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = CREATE_TARGET.matcher(sql);
            if (!m.find()) return null;
            String ident = m.group(1).replace("`", "").replace("\"", "");
            if (ident.indexOf('.') > 0) return ident;
            return ident.isBlank() ? null : defaultDatabase + "." + ident;
        } catch (IOException e) {
            return null;
        }
    }

    private static String preprocess(String sql, String canonical) {
        sql = sql.replaceFirst("(?i)CREATE\\s+EXTERNAL\\s+TABLE", "CREATE TABLE");
        Matcher m = CREATE_TARGET.matcher(sql);
        if (m.find()) {
            String ident = m.group(1).replace("`", "").replace("\"", "");
            if (!ident.equalsIgnoreCase(canonical)) {
                sql = sql.substring(0, m.start(1)) + canonical + sql.substring(m.end(1));
            }
        }
        sql = sql.replaceFirst("(?i)CREATE\\s+TABLE(?!\\s+IF\\s+NOT)", "CREATE TABLE IF NOT EXISTS");
        sql = sql.replaceAll("(?i)\\b(varchar|char)\\b(\\s*\\(\\s*\\d+\\s*\\))?", "string");
        return sql;
    }

    private static String tableOf(String canonical) { return canonical.substring(canonical.indexOf('.') + 1); }

    /** Physical DuckDB schema for a canonical db.table name (default -> main). */
    private static String physicalSchema(String canonical) {
        String db = canonical.substring(0, canonical.indexOf('.'));
        return "default".equalsIgnoreCase(db) ? "main" : db;
    }

    /** Quoted physical reference; main keeps the bare name. */
    private static String physicalRef(String schema, String table) {
        if ("main".equalsIgnoreCase(schema)) return "\"" + table.replace("\"", "\"\"") + "\"";
        return "\"" + schema.replace("\"", "\"\"") + "\".\"" + table.replace("\"", "\"\"") + "\"";
    }

    /**
     * Snapshot of the physical DuckDB table set across all schemas, taken once
     * before the import loop. Entries are lowercased {@code schema.table}
     * pairs; together with the in-memory set of names created during the run
     * they decide whether a failed import entry created its physical table,
     * so cleanup only drops what this import brought into existence and never
     * a pre-existing table with the same bare name in any schema.
     */
    private static Set<String> physicalTables(DuckDbExecutor executor) {
        Set<String> names = new LinkedHashSet<>();
        try {
            for (List<Object> row : executor.execute(
                    "SELECT table_schema, table_name FROM information_schema.tables").rows()) {
                names.add((String.valueOf(row.get(0)) + "." + row.get(1)).toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception ignore) {
            // best effort only: without a snapshot cleanup falls back to the
            // catalog-level DROP (qualified), never the bare physical name
            return null;
        }
        return names;
    }

    private static String oneLine(String m) {
        if (m == null) return "";
        return String.join(" ", m.split("\\r?\\n")).trim();
    }

    private DdlImporter() {}
}
