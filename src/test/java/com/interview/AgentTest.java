package com.interview;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class AgentTest {
    final ObjectMapper json=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    Contracts contracts;
    HttpServer upstream;
    AtomicReference<JsonNode> received=new AtomicReference<>();
    AtomicReference<String> requestedPath=new AtomicReference<>();
    String response; int code;
    @BeforeEach void setup() throws Exception {
        contracts=new Contracts(json); code=200;
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/",exchange->{
            received.set(json.readTree(exchange.getRequestBody())); requestedPath.set(exchange.getRequestURI().getPath());
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();
    }
    @AfterEach void cleanup(){upstream.stop(0);}
    ObjectNode input() throws Exception {return contracts.validate(json.readTree(ApplicationTest.INPUT));}
    AgentService service(String mode) {
        return new AgentService(json,contracts,new MockEnvironment().withProperty("OPENAI_API_KEY","test-key")
            .withProperty("OPENAI_MODEL","test-model").withProperty("AI_API_MODE",mode)
            .withProperty("OPENAI_BASE_URL","http://127.0.0.1:"+upstream.getAddress().getPort()+"/v1"));
    }
    String turn(){return """
      {"question":"你如何处理一次优先级冲突？","focus":"决策","kind":"opening","feedback":{"evidence":"should clear","strength":"x","gap":"x","nextStep":"x"}}
      """;}
    void responses(String text,String status) throws Exception {response=json.writeValueAsString(Map.of("status",status,"output",new Object[]{Map.of("content",new Object[]{Map.of("type","output_text","text",text)})}));}
    @Test void responsesContractAndFirstTurnFeedback() throws Exception {
        responses(turn(),"completed"); JsonNode result=service("responses").run(input());
        assertEquals("/v1/responses",requestedPath.get()); assertFalse(received.get().path("store").asBoolean());
        assertTrue(received.get().at("/text/format/strict").asBoolean());
        assertEquals("",result.at("/feedback/evidence").asText());
        assertTrue(received.get().path("instructions").asText().contains("不得编造"));
    }
    @Test void chatContractAndRealisticFeedback() throws Exception {
        response=json.writeValueAsString(Map.of("choices",new Object[]{Map.of("finish_reason","stop","message",Map.of("content",turn()))}));
        var in=input();((ObjectNode)in.get("config")).put("style","realistic");
        ((ArrayNode)in.get("history")).addObject().put("role","interviewer").put("text","说明贡献");
        ((ArrayNode)in.get("history")).addObject().put("role","candidate").put("text","我负责协调");
        JsonNode result=service("chat").run(in);
        assertEquals("/v1/chat/completions",requestedPath.get());assertEquals("",result.at("/feedback/gap").asText());
        assertTrue(received.get().at("/response_format/json_schema/strict").asBoolean());
    }
    @Test void incompleteAndInvalidOutputNeverBecomeResults() throws Exception {
        responses(turn(),"incomplete"); assertEquals(502,assertThrows(AppError.class,()->service("responses").run(input())).status);
        responses("{\"question\":\"missing fields\"}","completed");assertThrows(AppError.class,()->service("responses").run(input()));
        responses(turn()+" {}","completed");assertThrows(AppError.class,()->service("responses").run(input()));
    }
    @Test void upstreamSecretsNotReflected() throws Exception {
        code=401;response="provider secret test-key";
        AppError e=assertThrows(AppError.class,()->service("responses").run(input()));
        assertFalse(e.getMessage().contains("test-key"));assertEquals(502,e.status);
    }
    @Test void validationRejectsBrokenConversationAndMissingJd() throws Exception {
        var in=input();((ObjectNode)in.get("config")).put("type","job");final var missingJd=in;assertThrows(AppError.class,()->contracts.validate(missingJd));
        in=input();((ArrayNode)in.get("history")).addObject().put("role","candidate").put("text","跳过问题");
        final var broken=in;assertThrows(AppError.class,()->contracts.validate(broken));
        var review=input();review.put("action","review");assertThrows(AppError.class,()->contracts.validate(review));
    }
    @Test void prepareAndReviewReturnStrictSchemaResults() throws Exception {
        for (String action:new String[]{"prepare","review"}) {
            var in=input();in.put("action",action);
            String result=action.equals("prepare") ? "{\"summary\":\"待补充简历\",\"topics\":[],\"missing\":[\"简历\"]}" : "{\"summary\":\"本次练习反馈\",\"dimensions\":[],\"strengths\":[],\"gaps\":[],\"improvedAnswer\":\"[待补充]\",\"practice\":[]}";
            if(action.equals("review")){((ArrayNode)in.get("history")).addObject().put("role","interviewer").put("text","说明贡献");((ArrayNode)in.get("history")).addObject().put("role","candidate").put("text","我负责协调");}
            responses(result,"completed");assertTrue(service("responses").run(contracts.validate(in)).has("summary"));
        }
    }
    @Test void deepSeekUsesJsonObjectWithSchemaInPromptForEveryAction() throws Exception {
        for (String base : new String[]{"https://api.deepseek.com", "https://api.deepseek.com/v1/"}) {
            AgentService agent=new AgentService(json,contracts,new MockEnvironment()
                .withProperty("OPENAI_BASE_URL",base).withProperty("AI_API_MODE","chat")
                .withProperty("OPENAI_MODEL","deepseek-flash"));
            for (String action : new String[]{"prepare","turn","review"}) {
                var in=input(); in.put("action",action);
                JsonNode payload=agent.requestPayload(in);
                assertEquals("json_object",payload.at("/response_format/type").asText());
                assertEquals(1,payload.path("response_format").size());
                assertEquals("disabled",payload.at("/thinking/type").asText());
                assertTrue(payload.at("/messages/0/content").asText().contains(contracts.schemas.get(action).toString()));
                assertEquals("deepseek-flash",payload.path("model").asText());
            }
        }
    }
    @Test void deepSeekAdaptationDoesNotChangeOtherGateways() throws Exception {
        JsonNode payload=service("chat").requestPayload(input());
        assertEquals("json_schema",payload.at("/response_format/type").asText());
        assertFalse(payload.has("thinking"));
        AgentService lookalike=new AgentService(json,contracts,new MockEnvironment()
            .withProperty("OPENAI_BASE_URL","https://api.deepseek.com.example.org")
            .withProperty("AI_API_MODE","chat"));
        assertEquals("json_schema",lookalike.requestPayload(input()).at("/response_format/type").asText());
    }

}
