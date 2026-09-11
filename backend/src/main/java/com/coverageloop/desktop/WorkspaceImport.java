package com.coverageloop.desktop;

import org.sqlite.SQLiteConfig;
import java.nio.file.*;
import java.sql.*;

/** One-time consistent snapshot for the Tauri trial, including committed WAL data. */
final class WorkspaceImport {
    private WorkspaceImport() {}

    static void importIfNeeded(Path source, Path target) throws Exception {
        if (source == null || Files.exists(target)) return;
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("旧工作区不存在：" + source);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path staging = target.resolveSibling(target.getFileName() + ".import-" + java.util.UUID.randomUUID());
        SQLiteConfig options = new SQLiteConfig();
        options.setReadOnly(true);
        options.setBusyTimeout(5000);
        try {
            try (Connection db = options.createConnection("jdbc:sqlite:" + source.toAbsolutePath())) {
                try (Statement check = db.createStatement(); ResultSet tables = check.executeQuery(
                        "SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('projects','configurations','jobs','rounds')")) {
                    if (!tables.next() || tables.getInt(1) != 4) throw new IllegalArgumentException("旧工作区数据库结构无效");
                }
                try (PreparedStatement backup = db.prepareStatement("VACUUM INTO ?")) {
                    backup.setString(1, staging.toAbsolutePath().toString());
                    backup.execute();
                }
            }
            try (Connection imported = DriverManager.getConnection("jdbc:sqlite:" + staging.toAbsolutePath())) {
                java.util.List<String[]> rows = new java.util.ArrayList<>();
                try (Statement query = imported.createStatement(); ResultSet jobs = query.executeQuery("SELECT id,content FROM jobs")) {
                    while (jobs.next()) {
                        var content = com.google.gson.JsonParser.parseString(jobs.getString(2)).getAsJsonObject();
                        content.addProperty("importedFromLegacy", true);
                        rows.add(new String[]{jobs.getString(1),content.toString()});
                    }
                }
                try (PreparedStatement update = imported.prepareStatement("UPDATE jobs SET content=? WHERE id=?")) {
                    for (String[] row : rows) { update.setString(1,row[1]);update.setString(2,row[0]);update.addBatch(); }
                    update.executeBatch();
                }
            }
            // No REPLACE_EXISTING: a workspace created in the meantime must be kept.
            Files.move(staging, target);
        } finally { Files.deleteIfExists(staging); }
    }
}
