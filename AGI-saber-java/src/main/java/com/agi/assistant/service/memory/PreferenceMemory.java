package com.agi.assistant.service.memory;

import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class PreferenceMemory {

    private final Map<String, String> data = new ConcurrentHashMap<>();

    public void save(String key, String value) {
        if (key != null && !key.isEmpty() && value != null && !value.isEmpty()) {
            data.put(key, value);
        }
    }

    public void saveBatch(Map<String, String> kvs) {
        if (kvs != null) {
            kvs.forEach((k, v) -> {
                if (k != null && !k.isEmpty() && v != null && !v.isEmpty()) {
                    data.put(k, v);
                }
            });
        }
    }

    /**
     * Rule-based preference extraction from user message.
     * Returns [key, value] or null if nothing extracted.
     */
    public String[] extractAndSave(String msg) {
        if (msg.contains("我喜欢")) {
            String[] parts = msg.split("喜欢", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                String key = "喜好", value = parts[1].trim();
                data.put(key, value);
                return new String[]{key, value};
            }
        }
        if (msg.contains("我爱")) {
            String[] parts = msg.split("爱", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                String key = "喜好", value = parts[1].trim();
                data.put(key, value);
                return new String[]{key, value};
            }
        }
        if (msg.contains("我叫")) {
            String[] parts = msg.split("叫", 2);
            if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                String key = "姓名", value = parts[1].trim();
                data.put(key, value);
                return new String[]{key, value};
            }
        }
        return null;
    }

    public String buildContext() {
        if (data.isEmpty()) return "";
        String items = data.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining("\n"));
        return "【用户偏好】\n" + items;
    }

    public Map<String, String> getData() { return new LinkedHashMap<>(data); }
}
