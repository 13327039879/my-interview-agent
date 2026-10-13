package com.interview;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Service
class AgentService {
    private final ObjectMapper json;
    private final Contracts contracts;
    private final String key, model, mode, base;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Semaphore slots = new Semaphore(2);
    AgentService(ObjectMapper json, Contracts contracts, Environment env) {
        this.json=json; this.contracts=contracts;
        key=env.getProperty("OPENAI_API_KEY",""); model=env.getProperty("OPENAI_MODEL","");
        mode=env.getProperty("AI_API_MODE","responses");
        base=env.getProperty("OPENAI_BASE_URL","https://api.openai.com/v1").replaceAll("/+$", "");
    }
    boolean configured() { return !key.isBlank() && !model.isBlank() && (mode.equals("responses") || mode.equals("chat")); }
    String model() { return model; }
    JsonNode run(ObjectNode input) {
        if (!configured()) throw new AppError("AI 尚未配置：请设置云端环境变量 OPENAI_API_KEY、OPENAI_MODEL 和 AI_API_MODE，再发布新版本。可先使用流程演示。",503);
        if (!slots.tryAcquire()) throw new AppError("正在处理其他请求，请稍后重试。",429);
        try { return call(input); } finally { slots.release(); }
    }
    ObjectNode requestPayload(ObjectNode input) {
        String action=input.path("action").asText(); JsonNode schema=contracts.schemas.get(action);
        ObjectNode payload=json.createObjectNode().put("model",model);
        boolean deepSeek = base.equals("https://api.deepseek.com") || base.equals("https://api.deepseek.com/v1");
        if (mode.equals("responses")) {
            payload.put("store",false).put("instructions",contracts.prompts.get(action)).put("input",input.toString()).put("max_output_tokens",4500);
            payload.putObject("text").putObject("format").put("type","json_schema").put("name","interview_"+action).put("strict",true).set("schema",schema);
        } else {
            ArrayNode messages=payload.putArray("messages");
            String instructions=contracts.prompts.get(action);
            if (deepSeek) instructions += "\n请只输出一个 JSON 对象，不要使用 Markdown 代码块。必须包含以下 JSON Schema 定义的所有字段，不添加额外字段；字段类型和枚举值必须一致：\n" + schema;
            messages.addObject().put("role","system").put("content",instructions);
            messages.addObject().put("role","user").put("content",input.toString());
            payload.put("max_tokens",4500);
            if (deepSeek) {
                payload.putObject("response_format").put("type","json_object");
                // Keep interactive turns within the request budget; DeepSeek defaults to thinking enabled.
                payload.putObject("thinking").put("type","disabled");
            } else {
                payload.putObject("response_format").put("type","json_schema").putObject("json_schema")
                    .put("name","interview_"+action).put("strict",true).set("schema",schema);
            }
        }
        return payload;
    }
    private JsonNode call(ObjectNode input) {
        String action=input.path("action").asText(); JsonNode schema=contracts.schemas.get(action);
        ObjectNode payload=requestPayload(input);
        JsonNode data;
        try {
            HttpRequest request=HttpRequest.newBuilder(URI.create(base+(mode.equals("responses")?"/responses":"/chat/completions")))
                .timeout(Duration.ofSeconds(45)).header("Authorization","Bearer "+key).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
            HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
            if (response.statusCode()<200 || response.statusCode()>=300) throw new AppError(response.statusCode()==429 ? "模型服务限流或额度不足，请稍后重试。" : "模型服务返回错误（"+response.statusCode()+"），请检查云端网络、模型和密钥配置。",502);
            data=json.readTree(response.body());
        } catch (AppError e) { throw e; }
          catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AppError("模型请求已中断，请重试。",502); }
          catch (Exception e) { throw new AppError("模型连接失败、超时或返回格式错误。输入已保留，请稍后重试。",502); }
        String raw;
        if (mode.equals("responses")) {
            StringBuilder content=new StringBuilder();
            for (JsonNode out : data.path("output")) for (JsonNode part : out.path("content")) {
                if (part.path("type").asText().equals("refusal")) throw new AppError("模型未能处理这次内容。请调整材料后重试。",422);
                if (part.path("type").asText().equals("output_text")) content.append(part.path("text").asText());
            }
            if (!data.path("status").asText().equals("completed")) throw new AppError("模型响应未完成，尚未保存结果，请重试。",502);
            raw=content.toString();
        } else {
            JsonNode choice=data.path("choices").path(0);
            if (!choice.path("message").path("refusal").asText("").isBlank()) throw new AppError("模型未能处理这次内容。请调整材料后重试。",422);
            if (!choice.path("finish_reason").asText().equals("stop")) throw new AppError("模型响应未完成，尚未保存结果，请重试。",502);
            raw=choice.path("message").path("content").asText();
        }
        JsonNode result;
        try { result=json.readTree(raw); } catch (Exception e) { throw new AppError("模型输出格式不正确，尚未保存结果，请重试。",502); }
        if (!contracts.validOutput(result,schema)) throw new AppError("模型输出未通过结构校验，请重试。",502);
        if (action.equals("turn")) {
            if (result.path("question").asText().isBlank()) throw new AppError("模型没有返回问题，请重试。",502);
            if (input.path("history").isEmpty() || input.path("config").path("style").asText().equals("realistic"))
                ((ObjectNode)result).set("feedback",json.valueToTree(Map.of("evidence","","strength","","gap","","nextStep","")));
        }
        return result;
    }
}
