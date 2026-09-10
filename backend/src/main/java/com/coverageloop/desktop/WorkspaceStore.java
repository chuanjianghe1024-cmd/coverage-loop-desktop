package com.coverageloop.desktop;

import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.service.ConfigStore;
import com.coverageloop.util.Json;
import com.google.gson.*;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** SQLite is the source of truth for projects, configs, jobs and immutable round snapshots. */
public final class WorkspaceStore implements AutoCloseable {
    private final Connection db;
    public WorkspaceStore(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Class.forName("org.sqlite.JDBC");
            db = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
            try (Statement s = db.createStatement()) {
                s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA journal_mode=WAL"); s.execute("PRAGMA busy_timeout=5000");
                s.execute("CREATE TABLE IF NOT EXISTS projects(root_pom TEXT PRIMARY KEY, name TEXT NOT NULL, opened_at TEXT NOT NULL)");
                s.execute("CREATE TABLE IF NOT EXISTS configurations(root_pom TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, content TEXT NOT NULL, updated_at TEXT NOT NULL, PRIMARY KEY(root_pom,id))");
                s.execute("CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY, root_pom TEXT NOT NULL, status TEXT NOT NULL, started_at TEXT NOT NULL, content TEXT NOT NULL)");
                s.execute("CREATE INDEX IF NOT EXISTS jobs_project_time ON jobs(root_pom,started_at DESC)");
                s.execute("CREATE TABLE IF NOT EXISTS rounds(job_id TEXT NOT NULL REFERENCES jobs(id), round_number INTEGER NOT NULL, content TEXT NOT NULL, PRIMARY KEY(job_id,round_number))");
                s.execute("PRAGMA user_version=1");
            }
            for (JsonObject job : rows("SELECT content FROM jobs WHERE status IN ('running','stopping')")) {
                job.addProperty("status","interrupted"); job.addProperty("message","上次应用退出时任务未完成；请检查代码后重新运行基线"); job.addProperty("finishedAt",Instant.now().toString());
                update("UPDATE jobs SET status='interrupted', content=? WHERE id=?", job.toString(), job.get("id").getAsString());
            }
        } catch (Exception e) { throw new IllegalStateException("无法打开 SQLite 工作区：" + e.getMessage(), e); }
    }
    public static Path defaultPath() {
        String directory = System.getenv("COVERAGE_DATA_DIR");
        return Path.of(directory == null || directory.isBlank() ? Path.of(System.getProperty("user.home"),".coverage-loop").toString() : directory,"workspace.db");
    }
    private void update(String sql, Object... args) throws SQLException {
        try (PreparedStatement s = db.prepareStatement(sql)) { bind(s,args); s.executeUpdate(); }
    }
    private static void bind(PreparedStatement s, Object[] args) throws SQLException { for (int i=0;i<args.length;i++) s.setObject(i+1,args[i]); }
    private List<JsonObject> rows(String sql, Object... args) throws SQLException {
        List<JsonObject> values = new ArrayList<>();
        try (PreparedStatement s = db.prepareStatement(sql)) {
            bind(s,args); try(ResultSet rs=s.executeQuery()) { while(rs.next()) values.add(JsonParser.parseString(rs.getString(1)).getAsJsonObject()); }
        }
        return values;
    }
    public synchronized void touchProject(String root, String name) throws SQLException {
        update("INSERT INTO projects VALUES(?,?,?) ON CONFLICT(root_pom) DO UPDATE SET name=excluded.name,opened_at=excluded.opened_at",root,name,Instant.now().toString());
    }
    public synchronized List<Map<String,String>> projects() throws SQLException {
        List<Map<String,String>> items=new ArrayList<>();
        try(Statement s=db.createStatement();ResultSet r=s.executeQuery("SELECT root_pom,name,opened_at FROM projects ORDER BY opened_at DESC LIMIT 20")) {
            while(r.next()) items.add(Map.of("rootPomPath",r.getString(1),"name",r.getString(2),"openedAt",r.getString(3)));
        }
        return items;
    }
    public synchronized void saveConfig(ProjectConfig c) throws SQLException {
        c.updatedAt=Instant.now().toString();
        update("INSERT INTO configurations VALUES(?,?,?,?,?) ON CONFLICT(root_pom,id) DO UPDATE SET name=excluded.name,content=excluded.content,updated_at=excluded.updated_at",c.rootPomPath,c.id,c.name,Json.toCompactJson(c),c.updatedAt);
    }
    public synchronized List<ProjectConfig> configs(String root) throws SQLException {
        List<ProjectConfig> result=new ArrayList<>();
        for(JsonObject row:rows("SELECT content FROM configurations WHERE root_pom=? ORDER BY updated_at DESC",root)) result.add(ConfigStore.parseConfig(row.toString(),root));
        return result;
    }
    public synchronized ProjectConfig config(String root,String id) throws SQLException {
        return configs(root).stream().filter(c -> c.id.equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("未找到配置"));
    }
    public synchronized void saveJob(Map<String,Object> snapshot,ProjectConfig c) throws SQLException {
        Map<String,Object> value=new LinkedHashMap<>(snapshot); value.remove("events"); value.put("project",c.rootPomPath); value.put("name",c.name); value.put("config",c);
        update("INSERT INTO jobs VALUES(?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET status=excluded.status,content=excluded.content",snapshot.get("id"),c.rootPomPath,snapshot.get("status"),snapshot.get("startedAt"),Json.toCompactJson(value));
    }
    public synchronized void saveRound(String jobId,MavenRunResult round) throws SQLException {
        update("INSERT INTO rounds VALUES(?,?,?) ON CONFLICT(job_id,round_number) DO NOTHING",jobId,round.round,Json.toCompactJson(round));
    }
    public synchronized List<JsonObject> history(String root) throws SQLException {
        List<JsonObject> result=new ArrayList<>();
        for(JsonObject row:rows("SELECT content FROM jobs WHERE root_pom=? ORDER BY started_at DESC LIMIT 30",root)) {
            JsonObject summary=new JsonObject();
            for(String key:List.of("id","status","mode","message","startedAt","finishedAt","name")) if(row.has(key)) summary.add(key,row.get(key));
            result.add(summary);
        }
        return result;
    }
    public synchronized JsonObject detail(String root,String id) throws SQLException {
        List<JsonObject> found=rows("SELECT content FROM jobs WHERE root_pom=? AND id=?",root,id);
        if(found.isEmpty()) throw new IllegalArgumentException("未找到任务");
        JsonObject value=found.get(0); JsonArray rounds=new JsonArray();
        for(JsonObject row:rows("SELECT content FROM rounds WHERE job_id=? ORDER BY round_number",id)) rounds.add(row);
        value.add("rounds",rounds); return value;
    }
    @Override public synchronized void close() { try {db.close();} catch(SQLException ignored) {} }
}
