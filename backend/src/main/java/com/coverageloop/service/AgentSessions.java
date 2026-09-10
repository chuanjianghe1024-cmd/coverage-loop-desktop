package com.coverageloop.service;

import com.coverageloop.model.*;
import com.coverageloop.util.*;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Native CLI conversation identifiers, distinct from Coverage Loop run IDs. */
public final class AgentSessions {
    private AgentSessions(){}
    public static String sessionId(String provider,String output){
        String clean=Text.stripAnsi(output);String found=null;
        Pattern pattern="hermes".equals(provider)
            ?Pattern.compile("(?i)(?:session[_ ]?id\\s*[:=]\\s*|--resume\\s+|session\\s*:\\s*)([0-9]{8}_[0-9]{6}_[A-Za-z0-9]+)")
            :Pattern.compile("[\\\"]?session(?:ID|Id|_id)[\\\"]?\\s*[:=]\\s*[\\\"]?(ses_[A-Za-z0-9]+)");
        Matcher matcher=pattern.matcher(clean);while(matcher.find())found=matcher.group(1);return found;
    }
    public static String readable(String output){
        StringBuilder text=new StringBuilder();
        for(String line:output.split("\\R")){try{JsonObject event=JsonParser.parseString(line).getAsJsonObject();if(event.has("part")&&event.getAsJsonObject("part").has("text"))text.append(event.getAsJsonObject("part").get("text").getAsString()).append('\n');}catch(Exception ignored){text.append(line).append('\n');}}
        return text.toString();
    }
    public static void persist(MavenRunResult result,AgentRoundResult agent,ProjectConfig config,String output){
        agent.sessionId=sessionId(config.agent.provider,output);agent.provider=config.agent.provider;
        Path file=Path.of(result.runDirectory,"round-"+Names.roundLabel(result.round)+"-session.json");
        JsonObject session=Files.isRegularFile(file)?JsonParser.parseString(Fs.readString(file.toString())).getAsJsonObject():new JsonObject();
        session.addProperty("agentSessionId",agent.sessionId);session.addProperty("agentProvider",agent.provider);session.addProperty("agentFinishedAt",agent.finishedAt);
        Fs.writeString(file.toString(),Json.toJson(session)+"\n");
        Fs.writeString(Path.of(result.runDirectory,"session.json").toString(),Json.toJson(session)+"\n");
    }
    public static List<String> recoveryArgs(ProjectConfig config,String id){
        if(id==null||!("hermes".equals(config.agent.provider)?id.matches("[0-9]{8}_[0-9]{6}_[A-Za-z0-9]+") :id.matches("ses_[A-Za-z0-9]+")))throw new IllegalArgumentException("本轮没有可恢复的 Agent 会话 ID");
        List<String> args=new ArrayList<>();
        if("hermes".equals(config.agent.provider)){
            for(int i=0;i+1<config.agent.extraArgs.size();i++)if(config.agent.extraArgs.get(i).equals("--profile")){args.add("--profile");args.add(config.agent.extraArgs.get(i+1));break;}
            args.addAll(List.of("chat","--resume",id));
        }else if(!config.agent.opencodeAttach.isBlank())args.addAll(List.of("attach",config.agent.opencodeAttach,"--session",id));
        else args.addAll(List.of("--session",id));
        return args;
    }
}
