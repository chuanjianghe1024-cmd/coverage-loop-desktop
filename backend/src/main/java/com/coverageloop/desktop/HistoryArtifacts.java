package com.coverageloop.desktop;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Move only the selected job's generated evidence; never follow links during removal. */
final class HistoryArtifacts {
    private final Map<Path,Path> moved=new LinkedHashMap<>();
    private final List<Path> staging=new ArrayList<>();
    private static String string(JsonObject value,String key){return value.has(key)&&!value.get(key).isJsonNull()?value.get(key).getAsString():"";}
    static Set<Path> references(JsonObject job) {
        Set<Path> paths=new LinkedHashSet<>();
        if(job.has("rounds"))for(JsonElement element:job.getAsJsonArray("rounds")){
            String directory=string(element.getAsJsonObject(),"runDirectory");if(!directory.isBlank())paths.add(Path.of(directory).toAbsolutePath().normalize());
        }
        JsonObject probe=job.has("probe")&&!job.get("probe").isJsonNull()?job.getAsJsonObject("probe"):null;
        if(probe==null&&job.has("loop")&&!job.get("loop").isJsonNull()){
            JsonObject loop=job.getAsJsonObject("loop");if(loop.has("probe")&&!loop.get("probe").isJsonNull())probe=loop.getAsJsonObject("probe");
        }
        if(probe!=null){String log=string(probe,"logPath");if(!log.isBlank())paths.add(Path.of(log).toAbsolutePath().normalize());}
        return paths;
    }
    static HistoryArtifacts stage(String root,JsonObject job,Set<Path> otherReferences) throws IOException {
        HistoryArtifacts result=new HistoryArtifacts();
        Path project=Path.of(root).toAbsolutePath().normalize().getParent();
        if(!Files.exists(project))return result;
        project=project.toRealPath();Path runs=project.resolve(".coverage-loop/runs");
        String configId=job.getAsJsonObject("config").get("id").getAsString();
        List<Path> candidates=new ArrayList<>();
        for(Path path:references(job)){
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))continue;
            Path real=path.toRealPath();
            if(Files.isSymbolicLink(path)||!real.startsWith(runs)||!runs.toRealPath().equals(runs))throw new IOException("记录文件位于预期运行目录之外；可取消勾选文件清理后删除记录");
            Path relative=runs.relativize(real);
            boolean round=relative.getNameCount()==2&&!relative.getName(1).toString().equals("agent-probes")&&Files.isDirectory(real);
            boolean probe=relative.getNameCount()==3&&relative.getName(1).toString().equals("agent-probes")&&real.toString().endsWith(".log")&&Files.isRegularFile(real);
            if(!(round||probe)||!relative.getName(0).toString().equals(configId))throw new IOException("不是本次任务的运行产物，已取消文件清理");
            for(Path other:otherReferences){Path resolved=Files.exists(other)?other.toRealPath():other;if(resolved.startsWith(real)||real.startsWith(resolved))throw new IOException("此日志目录仍被其他运行记录引用；请保留文件");}
            candidates.add(real);
        }
        try{
            for(Path candidate:candidates){
                // A sibling staging directory keeps renames on the same filesystem.
                Path holding=Files.createTempDirectory(candidate.getParent(),".deleting-");result.staging.add(holding);
                Path to=holding.resolve(candidate.getFileName());Files.move(candidate,to);result.moved.put(candidate,to);
            }
            return result;
        }catch(IOException e){try{result.restore();}catch(IOException restore){e.addSuppressed(restore);}throw e;}
    }
    void restore() throws IOException {
        IOException failure=null;
        for(var entry:moved.entrySet())try{Files.move(entry.getValue(),entry.getKey());}catch(IOException e){failure=e;}
        for(Path directory:staging)try{Files.deleteIfExists(directory);}catch(IOException e){failure=e;}
        if(failure!=null)throw failure;
    }
    String remove() {
        for(Path directory:staging)try(var paths=Files.walk(directory)){
            for(Path file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);
        }catch(IOException e){return "记录已删除，部分日志文件被占用，暂保留在 "+directory;}
        return "";
    }
}
