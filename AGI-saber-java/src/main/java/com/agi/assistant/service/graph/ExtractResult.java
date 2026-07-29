package com.agi.assistant.service.graph;

import java.util.ArrayList;
import java.util.List;

/**
 * Extractor.extract 的输出结构（对应 Go graph.ExtractResult）
 */
public class ExtractResult {
    private List<Entity> entities = new ArrayList<>();
    private List<Relation> relations = new ArrayList<>();

    public List<Entity> getEntities() { return entities; }
    public void setEntities(List<Entity> entities) {
        this.entities = entities != null ? entities : new ArrayList<>();
    }

    public List<Relation> getRelations() { return relations; }
    public void setRelations(List<Relation> relations) {
        this.relations = relations != null ? relations : new ArrayList<>();
    }
}
