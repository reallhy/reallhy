package com.sds.minion.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sds.common.PropertyUtils;
import com.sds.minion.agent.domain.AgentInfo;
import com.sds.minion.agent.domain.AppStatus;
import com.sds.minion.agent.run.DbChecker;
import com.sds.minion.agent.run.ProcessRunner;
import com.sds.minion.agent.run.WebChecker;
import com.sds.minion.agent.service.AppService;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HTTP;
import org.springframework.stereotype.Repository;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Repository("appService")
public class AppServiceImpl implements AppService {

    private AgentInfo agentInfo;
    private Date timestamp;
    private Properties agentProp;
    private String appRootPath;
    private ObjectMapper objectMapper = new ObjectMapper();
    private Map<String, Properties> appProp = new HashMap<String, Properties>();
    private final int PUSH_INTERVAL = Integer.parseInt(PropertyUtils.getProperty("minion.push.interval"));

    @Override
    public AgentInfo getAgentInfo() {
        String memory = HealInfo.getMemoryUsage();
        String cpu = HealInfo.getCpuUsage();
        String disk = HealInfo.getDiskUsage();
        if (agentInfo != null) {
            reload();
            agentInfo.setCpu(cpu);
            agentInfo.setMemory(memory);
            agentInfo.setDisk(disk);
            return agentInfo;
        }
        String agentRoot = new File("").getAbsolutePath();
        appRootPath = agentRoot + "/prop";
        agentProp = getProperity(new File(appRootPath + "/agent.properties"));

        agentInfo = new AgentInfo();
        agentInfo.setCpu(cpu);
        agentInfo.setMemory(memory);
        agentInfo.setDisk(disk);
        agentInfo.setPath(agentRoot);
        agentInfo.setName(agentProp.getProperty("agent.name"));
        agentInfo.setUrl(agentProp.getProperty("agent.url"));
        return agentInfo;
    }

    @Override
    public void runApp(String name, String run) {
        //run : start, stop, restart
        Properties prop = appProp.get(name);
        String cmd = "";
        String dir = prop.getProperty("app.run.dir");
        if (run.equals("stop")) {
            if (ProcessRunner.isWindows()) {
                cmd = prop.get("app.run.window.stop").toString();
            } else {
                cmd = prop.get("app.run.stop").toString();
            }
        } else if (run.equals("start")) {
            if (ProcessRunner.isWindows()) {
                cmd = prop.get("app.run.window.start").toString();
            } else {
                cmd = prop.get("app.run.start").toString();
            }
        } else {
            if (ProcessRunner.isWindows()) {
                cmd = prop.get("app.run.window.stop").toString();
            } else {
                cmd = prop.get("app.run.stop").toString();
            }
            ProcessRunner r = new ProcessRunner();
            r.runProcess(dir, cmd, true);
            if (ProcessRunner.isWindows()) {
                cmd = prop.get("app.run.window.start").toString();
            } else {
                cmd = prop.get("app.run.start").toString();
            }
        }
        ProcessRunner r = new ProcessRunner();
        r.runProcess(dir, cmd);

    }

    public AppServiceImpl() {
        agentInfo = getAgentInfo();
        reload();
        checkinStart();

    }

    private void checkinStart() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);
        scheduler.scheduleAtFixedRate(new Runnable() {
            public void run() {
                agentInfo = getAgentInfo();
                push(agentInfo);
            }
        }, 0, PUSH_INTERVAL, TimeUnit.SECONDS);
    }

    public void push(AgentInfo agentInfo) {
        try {
            HttpPost post = new HttpPost(agentProp.getProperty("merlin.url").toString() + "/checkIn");
            post.setHeader(HTTP.CONTENT_TYPE, "application/json; charset=utf-8");

            String json = objectMapper.writeValueAsString(agentInfo);
            HttpEntity entity = new StringEntity(json, "UTF-8");
            post.setEntity(entity);

            HttpClient client = HttpClients.createDefault();
            HttpResponse resp = client.execute(post);
            System.out.println("push!!! = " + json);
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    public void reload() {
        Date now = new Date();
        if (timestamp != null) {
            long gap = now.getTime() - timestamp.getTime();
//            System.out.println("gap:" + gap);
            if (gap < 1000) {
//                System.out.println("1분미미나.");
                return;
            } else {
                timestamp = now;
            }
        } else {
            timestamp = now;
        }
        List<AppStatus> appStatusList = new LinkedList<AppStatus>();
        File root = new File(appRootPath);
        if (!root.exists()) {
            root.mkdirs();
        }
        File[] list = root.listFiles();
        for (File f : list) {
            if (isApp(appStatusList, f) && f.isFile()) {
                appStatusList.add(loadApp(f));
                continue;
            }
        }
        agentInfo.setApps(appStatusList);
        //System.out.println("load ok!");
    }
    private boolean isApp(List<AppStatus> appStatusList, File f) {
        if(f.isDirectory() == true){
            File[] list = f.listFiles();
            for (File f2 : list) {
                if (isApp(appStatusList, f2) && f2.isFile()) {
                    appStatusList.add(loadApp(f2));
                    continue;
                }
            }
            return false;
        }
        //System.out.println("f.getName():"+f.getName());
        if(f.getName().equals("app.properties")){
            //System.out.println("return true!!!");
            return true;
        }
        return false;
    }
    private AppStatus loadApp(File f) {
        Properties prop = getProperity(f);
        appProp.put(prop.get("app.name").toString(), prop);
        String type = prop.get("app.health.type").toString();
        String check = prop.get("app.health.check").toString();
        String result = "";
        if (type.equals("db")) {
            String userId = prop.get("app.health.check.userId").toString();
            String password = prop.get("app.health.check.password").toString();
            result = DbChecker.check(check, userId, password);
        } else if (type.equals("web")) {
            result = WebChecker.check(check);
        }
        
        if(result.equals(AppStatus.DEAD) && prop.get("app.run.auto").toString().equals("true")){
        	runApp(prop.get("app.name").toString(), "restart");
   	 	}

        AppStatus appStatus = new AppStatus(prop.get("app.name").toString(), result);
        appStatus.setType(type);
        return appStatus;
    }

    private Properties getProperity(File f) {
        Properties prop = new Properties();
        InputStream inputStream;
        try {
            inputStream = new FileInputStream(f);
            prop.load(inputStream);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return prop;
    }

    public static void main(String[] args) throws InterruptedException {
        System.out.println("start");
        AppServiceImpl a = new AppServiceImpl();
        Thread.sleep(10000);
        a.reload();
        a.runApp("scout", "restart");
    }
}
