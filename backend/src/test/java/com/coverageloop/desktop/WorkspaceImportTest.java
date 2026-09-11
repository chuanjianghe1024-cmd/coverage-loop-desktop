package com.coverageloop.desktop;

import com.coverageloop.model.ConfigFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.sql.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceImportTest {
    @TempDir Path temp;

    @Test void importsCommittedWalDataOnceAndKeepsOriginalDatabaseUntouched() throws Exception {
        Path source=temp.resolve("旧 工作区.db"), target=temp.resolve("trial/workspace.db");
        String root=temp.resolve("pom.xml").toString();
        try (WorkspaceStore original=new WorkspaceStore(source)) {
            var config=ConfigFactory.createDefaultConfig(root);config.name="旧配置";
            original.saveConfig(config);original.touchProject(root,"demo");
            original.saveJob(java.util.Map.of("id","old-run","status","completed","startedAt","2026-09-11T00:00:00Z"),config);
            assertTrue(Files.exists(Path.of(source+"-wal")));
            WorkspaceImport.importIfNeeded(source,target);
            try (WorkspaceStore imported=new WorkspaceStore(target)) {
                assertEquals("旧配置",imported.config(root,config.id).name);
                assertEquals(1,imported.projects().size());
                assertTrue(imported.history(root).get(0).get("importedFromLegacy").getAsBoolean());
                assertThrows(IllegalStateException.class,()->imported.deleteJob(root,"old-run",true));
                imported.deleteJob(root,"old-run",false);
                config.name="试用版修改";imported.saveConfig(config);
            }
            WorkspaceImport.importIfNeeded(source,target);
            assertEquals("旧配置",original.config(root,config.id).name);
            assertEquals(1,original.history(root).size());
            try (WorkspaceStore imported=new WorkspaceStore(target)) {assertEquals("试用版修改",imported.config(root,config.id).name);}
        }
    }

    @Test void invalidSourceNeverCreatesAnEmptyWorkspace() throws Exception {
        Path source=temp.resolve("invalid.db"),target=temp.resolve("trial/workspace.db");
        try(Connection ignored=DriverManager.getConnection("jdbc:sqlite:"+source)){}
        assertThrows(IllegalArgumentException.class,()->WorkspaceImport.importIfNeeded(source,target));
        assertFalse(Files.exists(target));
        try(var files=Files.list(target.getParent())){assertEquals(0,files.count());}
    }
}
