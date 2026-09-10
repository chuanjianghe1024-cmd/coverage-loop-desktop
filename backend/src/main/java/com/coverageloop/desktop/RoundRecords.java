package com.coverageloop.desktop;

import com.coverageloop.model.ProjectConfig;
import com.coverageloop.service.AgentSessions;
import com.coverageloop.util.Json;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Only persisted rounds and a fixed set of evidence files may be opened. */
public final class RoundRecords {
    private static final Map<String,String> FILES=new LinkedHashMap<>();
    private static final int LIMIT=512*1024;
    static {
        FILES.put("coverage.json","coverage.json"); FILES.put("coverage-gate.txt","coverage-gate.txt");
        FILES.put("failed-classes.txt","failed-classes.txt"); FILES.put("maven.log","maven.log");
        FILES.put("summary.txt","test-summary.txt"); FILES.put("session.json","session.json");
    }
    private final WorkspaceStore store;
    public RoundRecords(WorkspaceStore store){this.store=store;}
    private record Record(JsonObject job,JsonObject round,Path root,Path directory,int number){}
    private Record record(JsonObject input) throws Exception {
        String root=input.get("rootPomPath").getAsString(),id=input.get("id").getAsString();
        int number=input.get("round").getAsInt();
        JsonObject job=store.detail(root,id),round=null;
        for(JsonElement item:job.getAsJsonArray("rounds"))if(item.getAsJsonObject().get("round").getAsInt()==number)round=item.getAsJsonObject();
        if(round==null)throw new IllegalArgumentException("未找到本轮记录");
        Path project=Path.of(root).toRealPath().getParent(),evidence=project.resolve(".coverage-loop").toRealPath();
        Path directory=Path.of(round.get("runDirectory").getAsString()).toRealPath();
        if(!evidence.startsWith(project)||!directory.startsWith(evidence.resolve("runs")))throw new IllegalArgumentException("运行记录不在当前工程中");
        return new Record(job,round,project,directory,number);
    }
    private Path path(Record record,String name) throws Exception {
        if(!FILES.containsKey(name))throw new IllegalArgumentException("不支持的记录文件");
        Path file=record.directory.resolve("round-"+String.format("%03d",record.number)+"-"+FILES.get(name));
        // Shared session.json belongs to the latest round; never substitute it for an older round.
        if(Files.exists(file)&&!file.toRealPath().startsWith(record.directory))throw new IllegalArgumentException("记录文件超出运行目录");
        return file;
    }
    public Object list(JsonObject input) throws Exception {
        Record record=record(input);List<Object> files=new ArrayList<>();
        for(String name:FILES.keySet()){
            Path file=path(record,name);boolean exists=Files.isRegularFile(file);
            files.add(Map.of("name",name,"path",file.toString(),"exists",exists,"size",exists?Files.size(file):0));
        }
        return Map.of("files",files,"directory",record.directory.toString(),"recovery",recovery(record));
    }
    public Object read(JsonObject input) throws Exception {
        Record record=record(input);Path file=path(record,input.get("name").getAsString());
        if(!Files.isRegularFile(file))return Map.of("content","本轮尚未生成此文件。旧版本记录可能没有独立的 session.json。","truncated",false);
        long size=Files.size(file);byte[] bytes;
        try(var in=Files.newInputStream(file)){bytes=in.readNBytes(LIMIT);}
        return Map.of("content",new String(bytes,StandardCharsets.UTF_8),"truncated",size>LIMIT,"size",size);
    }
    public Object recovery(JsonObject input) throws Exception {
        Map<String,Object> result=recovery(record(input));
        if(!Boolean.TRUE.equals(result.get("available")))throw new IllegalStateException(result.get("reason").toString());
        return result;
    }
    private Map<String,Object> recovery(Record record) throws Exception {
        if(!record.round.has("finishedAt")||record.round.get("finishedAt").isJsonNull())return Map.of("available",false,"reason","请等待本轮运行结束");
        String id=null;int origin=0;
        if(record.job.has("agentRounds"))for(JsonElement item:record.job.getAsJsonArray("agentRounds")){
            JsonObject agent=item.getAsJsonObject();int n=agent.get("round").getAsInt();
            if(n<=record.number&&n>=origin&&agent.has("sessionId")&&!agent.get("sessionId").isJsonNull()&&agent.has("finishedAt")){
                id=agent.get("sessionId").getAsString();origin=n;
            }
        }
        ProjectConfig config=Json.fromJson(record.job.get("config").toString(),ProjectConfig.class);
        if(id==null){ // Older logs may already contain the native identifier.
            for(int n=record.number;n>=1;n--){
                Path log=record.directory.resolve("round-"+String.format("%03d",n)+"-agent.log");
                if(Files.isRegularFile(log)&&log.toRealPath().startsWith(record.directory)){
                    try(var channel=Files.newByteChannel(log)){
                        channel.position(Math.max(0,channel.size()-2*1024*1024));
                        var buffer=java.nio.ByteBuffer.allocate((int)Math.min(channel.size(),2*1024*1024));
                        while(buffer.hasRemaining()&&channel.read(buffer)>0){}
                        id=AgentSessions.sessionId(config.agent.provider,new String(buffer.array(),0,buffer.position(),StandardCharsets.UTF_8));
                    }
                    if(id!=null){origin=n;break;}
                }
            }
        }
        if(id==null)return Map.of("available",false,"reason","本轮没有记录可恢复的 Agent 会话；仅运行 Maven 时不会创建助手会话");
        String executable=config.agent.executable.isBlank()?config.agent.provider:config.agent.executable;
        List<String> args=AgentSessions.recoveryArgs(config,id);
        return Map.of("available",true,"sessionId",id,"originRound",origin,"provider",config.agent.provider,"executable",executable,"args",args,"cwd",record.root.toString());
    }
}
