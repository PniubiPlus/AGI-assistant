package com.agi.assistant.service.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * Extractor 通过注入的 LLM 回调从文本中抽取实体和关系
 * （对应 Go internal/graph/extractor.go）
 */
public class Extractor {

    private static final Logger log = LoggerFactory.getLogger(Extractor.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private static final Set<String> VALID_RELATIONS = new HashSet<>(Arrays.asList(
            "RELATES_TO", "PART_OF", "CAUSES", "DESCRIBES", "MENTIONS", "WORKS_FOR", "LOCATED_IN"
    ));

    private static final String EXTRACT_SYSTEM_PROMPT = """
            你是一个信息抽取专家。从给定文本中抽取命名实体和实体间关系。

            实体类型（type 字段只能用以下值）：
            - Person（人物）
            - Organization（组织/公司/机构）
            - Location（地点/地区）
            - Concept（概念/技术/思想）
            - Event（事件）
            - Product（产品/工具）
            - Unknown（其他）

            关系类型（rel_type 字段只能用以下值）：
            - RELATES_TO（相关）
            - PART_OF（属于/是...的一部分）
            - CAUSES（导致/引发）
            - DESCRIBES（描述/介绍）
            - MENTIONS（提及）
            - WORKS_FOR（工作于）
            - LOCATED_IN（位于）

            输出格式（只输出 JSON，不加任何说明）：
            {
              "entities": [{"name":"实体名","type":"类型"}],
              "relations": [{"from":"实体A","to":"实体B","rel_type":"关系类型"}]
            }

            如果文本中没有可抽取的实体，输出 {"entities":[],"relations":[]}""";

    /** LLM 回调（systemPrompt, userMsg) -> 回复 */
    private final BiFunction<String, String, String> llmFn;

    public Extractor(BiFunction<String, String, String> llmFn) {
        this.llmFn = llmFn;
    }

    /**
     * 从文本中抽取实体和关系。LLM 不可用或解析失败返回空结果。
     */
    public ExtractResult extract(String text) {
        ExtractResult empty = new ExtractResult();
        if (llmFn == null || text == null || text.trim().isEmpty()) {
            return empty;
        }
        String raw;
        try {
            raw = llmFn.apply(EXTRACT_SYSTEM_PROMPT, "文本：\n" + text);
        } catch (Exception e) {
            log.warn("Extractor LLM 调用失败: {}", e.getMessage());
            return empty;
        }
        if (raw == null) return empty;
        raw = raw.trim()
                .replace("```json", "")
                .replace("```", "")
                .trim();

        ExtractResult result = new ExtractResult();
        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode entities = root.get("entities");
            if (entities != null && entities.isArray()) {
                Set<String> seen = new HashSet<>();
                for (JsonNode e : entities) {
                    String name = e.has("name") ? e.get("name").asText("").trim() : "";
                    if (name.isEmpty() || seen.contains(name)) continue;
                    String type = e.has("type") ? e.get("type").asText("Unknown") : "Unknown";
                    Entity ent = new Entity(name, EntityType.fromValue(type));
                    result.getEntities().add(ent);
                    seen.add(name);
                }
            }
            JsonNode rels = root.get("relations");
            if (rels != null && rels.isArray()) {
                for (JsonNode r : rels) {
                    String from = r.has("from") ? r.get("from").asText("").trim() : "";
                    String to = r.has("to") ? r.get("to").asText("").trim() : "";
                    String rt = r.has("rel_type") ? r.get("rel_type").asText("RELATES_TO") : "RELATES_TO";
                    if (from.isEmpty() || to.isEmpty() || rt.isEmpty()) continue;
                    if (!VALID_RELATIONS.contains(rt)) rt = "RELATES_TO";
                    result.getRelations().add(new Relation(from, to, rt));
                }
            }
        } catch (Exception ex) {
            log.warn("实体关系抽取解析失败: {} (原始输出: {})",
                    ex.getMessage(),
                    raw.length() > 100 ? raw.substring(0, 100) : raw);
            return empty;
        }
        return result;
    }

    public static boolean isValidRelType(String r) {
        return r != null && VALID_RELATIONS.contains(r);
    }
}
