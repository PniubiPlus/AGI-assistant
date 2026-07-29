package com.agi.assistant.service.tools;

import com.agi.assistant.model.Tool;
import com.agi.assistant.model.ToolCallResult;
import com.agi.assistant.model.ToolParam;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ToolService {

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final OkHttpClient httpClient = new OkHttpClient();

    private static final Map<String, String> WEATHER_DB = Map.of(
            "北京", "晴天 22°C",
            "东京", "多云 18°C 湿度65%",
            "上海", "小雨 20°C",
            "纽约", "晴天 15°C",
            "伦敦", "阴天 12°C",
            "广州", "晴天 28°C",
            "深圳", "晴天 26°C"
    );

    public Map<String, Tool> getDefaultTools() {
        Map<String, Tool> tools = new ConcurrentHashMap<>();
        tools.put("get_time", createGetTimeTool());
        tools.put("get_weather", createGetWeatherTool());
        tools.put("search_web", createSearchWebTool());
        return tools;
    }

    private Tool createGetTimeTool() {
        return new Tool("get_time", "获取当前时间",
                List.of(new ToolParam("timezone", "string", "时区（如 Asia/Tokyo）", false)),
                params -> {
                    String tz = params.get("timezone") != null ? params.get("timezone").toString() : "";
                    ZoneId zone = ZoneId.systemDefault();
                    if (!tz.isEmpty()) {
                        try { zone = ZoneId.of(tz); } catch (Exception ignored) {}
                    }
                    return ZonedDateTime.now(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                });
    }

    private Tool createGetWeatherTool() {
        return new Tool("get_weather", "获取城市天气信息",
                List.of(new ToolParam("city", "string", "城市名称", true)),
                params -> {
                    String city = params.get("city") != null ? params.get("city").toString() : "北京";
                    String weather = WEATHER_DB.getOrDefault(city, "晴天 20°C（模拟）");
                    return city + "：" + weather;
                });
    }

    private Tool createSearchWebTool() {
        return new Tool("search_web", "搜索互联网获取最新信息",
                List.of(new ToolParam("query", "string", "搜索关键词", true)),
                params -> {
                    String q = params.get("query") != null ? params.get("query").toString() : "";
                    // Mock search results
                    if (q.contains("AI应用工程师") || q.contains("AI工程师")) {
                        return "AI 应用工程师是将 AI 技术落地到业务的工程师，需具备 ML 基础、API 开发、Prompt 工程等能力。";
                    }
                    if (q.contains("Go语言") || q.contains("Go")) {
                        return "Go 是 Google 开发的开源编程语言，适用于高并发服务端应用。Docker 即用 Go 开发。";
                    }
                    return String.format("关于「%s」的搜索结果（模拟）", q);
                });
    }

    /**
     * Rule-based tool selection. Returns ToolCallResult or null.
     */
    public ToolCallResult decide(String query, Map<String, Tool> tools) {
        String q = query.toLowerCase();

        if ((q.contains("几点") || q.contains("时间")) && tools.containsKey("get_time")) {
            Map<String, Object> params = new HashMap<>();
            if (q.contains("东京")) params.put("timezone", "Asia/Tokyo");
            return new ToolCallResult("get_time", params);
        }

        if (q.contains("天气") && tools.containsKey("get_weather")) {
            String city = "北京";
            for (String c : List.of("东京", "北京", "上海", "纽约", "伦敦", "广州", "深圳")) {
                if (q.contains(c)) { city = c; break; }
            }
            return new ToolCallResult("get_weather", Map.of("city", city));
        }

        if ((q.contains("查") || q.contains("搜索") || q.contains("是什么")) && tools.containsKey("search_web")) {
            return new ToolCallResult("search_web", Map.of("query", query));
        }

        // Fallback: pick first tool
        for (String name : tools.keySet()) {
            return new ToolCallResult(name, Map.of("query", query));
        }
        return null;
    }

    /**
     * Create an MCP tool that calls an external HTTP endpoint.
     */
    public Tool createMCPTool(String name, String description, String endpoint, List<ToolParam> params) {
        Tool tool = new Tool(name, description, params, p -> {
            try {
                String json = mapper.writeValueAsString(p);
                RequestBody body = RequestBody.create(json, MediaType.parse("application/json"));
                Request request = new Request.Builder().url(endpoint).post(body).build();
                try (Response response = httpClient.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new RuntimeException("MCP 返回错误状态 " + response.code());
                    }
                    return response.body() != null ? response.body().string() : "";
                }
            } catch (Exception e) {
                throw new RuntimeException("MCP 请求失败 [" + endpoint + "]: " + e.getMessage());
            }
        });
        tool.setMcp(true);
        return tool;
    }
}
