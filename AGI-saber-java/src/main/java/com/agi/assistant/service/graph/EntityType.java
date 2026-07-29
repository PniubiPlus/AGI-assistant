package com.agi.assistant.service.graph;

/**
 * 实体类型枚举（对应 Go internal/graph/types.go EntityType）
 */
public enum EntityType {
    PERSON("Person"),
    ORGANIZATION("Organization"),
    LOCATION("Location"),
    CONCEPT("Concept"),
    EVENT("Event"),
    PRODUCT("Product"),
    UNKNOWN("Unknown");

    private final String value;

    EntityType(String value) { this.value = value; }

    public String value() { return value; }

    public static EntityType fromValue(String v) {
        if (v == null) return UNKNOWN;
        for (EntityType t : values()) {
            if (t.value.equalsIgnoreCase(v)) return t;
        }
        return UNKNOWN;
    }
}
