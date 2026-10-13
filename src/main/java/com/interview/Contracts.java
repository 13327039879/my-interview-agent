package com.interview;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
class Contracts {
    private final ObjectMapper json;
    final Map<String, JsonNode> schemas = new HashMap<>();
    final Map<String, String> prompts = new HashMap<>();
    Contracts(ObjectMapper json) throws IOException {
        this.json = json;
        for (String action : List.of("prepare", "turn", "review")) {
            try (var stream = new ClassPathResource(action + "-schema.json").getInputStream()) { schemas.put(action, json.readTree(stream)); }
            try (var stream = new ClassPathResource(action + "-prompt.txt").getInputStream()) { prompts.put(action, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        }
    }
    ObjectNode validate(JsonNode raw) {
        if (raw == null || !raw.isObject() || !schemas.containsKey(raw.path("action").asText())) throw new AppError("不支持的操作。");
        String action = raw.path("action").asText();
        JsonNode c = raw.path("config");
        if (!c.isObject()) throw new AppError("请先填写面试设置。");
        ObjectNode config = json.createObjectNode();
        for (var field : Map.of("role",120,"resume",18000,"jd",12000).entrySet()) {
            JsonNode value = c.path(field.getKey());
            if (!value.isTextual() || value.asText().length() > field.getValue()) throw new AppError(field.getKey() + " 字段格式或长度不正确。");
            config.put(field.getKey(), value.asText().strip());
        }
        if (config.path("role").asText().isBlank()) throw new AppError("请填写目标岗位。");
        for (var field : Map.of("style",List.of("coach","realistic"),"type",List.of("mixed","behavioral","job"),"language",List.of("zh","en")).entrySet()) {
            if (!c.path(field.getKey()).isTextual() || !field.getValue().contains(c.path(field.getKey()).asText())) throw new AppError("面试选项不正确。");
            config.put(field.getKey(), c.path(field.getKey()).asText());
        }
        if (!c.path("rounds").isIntegralNumber() || !c.path("rounds").canConvertToInt() || !List.of(4,6,8).contains(c.path("rounds").asInt())) throw new AppError("面试轮数不正确。");
        int rounds = c.path("rounds").asInt(); config.put("rounds", rounds);
        if (!config.path("type").asText().equals("behavioral") && config.path("jd").asText().isBlank()) throw new AppError("岗位匹配或综合面试需要目标 JD，也可以先选择行为面试。");
        JsonNode source = raw.path("history");
        if (!source.isArray() || source.size() > 16) throw new AppError("对话记录超过上限或格式错误。");
        ArrayNode history = json.createArrayNode();
        for (int i=0; i<source.size(); i++) {
            JsonNode h = source.get(i); String role = i%2==0 ? "interviewer" : "candidate";
            if (!h.path("role").asText().equals(role) || !h.path("text").isTextual() || h.path("text").asText().isBlank() || h.path("text").asText().length()>6000) throw new AppError("对话记录格式不正确。");
            history.addObject().put("role",role).put("text",h.path("text").asText().strip());
        }
        if (action.equals("prepare") && !history.isEmpty()) throw new AppError("准备分析不应包含面试记录。");
        if (action.equals("turn") && (history.size()%2!=0 || history.size()>=rounds*2)) throw new AppError("请完成当前回答，或结束面试生成复盘。");
        if (action.equals("review") && history.size()<2) throw new AppError("至少回答一个问题后才能生成复盘。");
        ObjectNode input = json.createObjectNode().put("action",action); input.set("config",config); input.set("history",history); return input;
    }
    boolean validOutput(JsonNode value, JsonNode schema) {
        if (value == null) return false;
        switch(schema.path("type").asText()) {
            case "string":
                if (!value.isTextual() || value.asText().length()>16000) return false;
                if (schema.has("enum")) { for (JsonNode option : schema.get("enum")) if (option.equals(value)) return true; return false; }
                return true;
            case "array":
                if (!value.isArray() || value.size()>20) return false;
                for (JsonNode item : value) if (!validOutput(item,schema.get("items"))) return false;
                return true;
            default:
                if (!value.isObject() || value.size()!=schema.path("required").size()) return false;
                for (JsonNode key : schema.path("required")) if (!validOutput(value.get(key.asText()),schema.path("properties").get(key.asText()))) return false;
                return true;
        }
    }
}
