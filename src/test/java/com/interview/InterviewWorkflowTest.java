package com.interview;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InterviewWorkflowTest {
    final ObjectMapper json = new ObjectMapper();
    Contracts contracts;
    AgentService agent;
    InterviewWorkflow workflow;
    ObjectNode config, plan, state;
    ArrayNode history;
    JsonNode assessment;
    int calls;
    @BeforeEach void setup() throws Exception {
        contracts=new Contracts(json); agent=mock(AgentService.class); workflow=new InterviewWorkflow(json,contracts,agent);
        config=(ObjectNode)json.readTree(ApplicationTest.INPUT).path("config");
        plan=(ObjectNode)json.readTree("""
            {"summary":"通用工程经理练习","dimensions":[
              {"id":"d1","title":"决策","goal":"了解取舍依据","question":"请讲一次关键技术决策。"},
              {"id":"d2","title":"协作","goal":"了解协作行动","question":"请讲一次解决团队分歧的经历。"},
              {"id":"d3","title":"交付","goal":"了解交付结果","question":"请讲一次推进项目交付的经历。"}]}
            """);
        assessment=json.readTree("{\"evidence\":[],\"followupQuestion\":\"你个人具体做了什么？\"}");
        when(agent.run(any())).thenAnswer(invocation->{
            calls++;
            JsonNode input=invocation.getArgument(0);
            return switch(input.path("action").asText()) {
                case "plan" -> plan.deepCopy();
                case "assess" -> assessment.deepCopy();
                case "reflect" -> json.readTree("""
                    {"summary":"表现很好","dimensions":[{"name":"决策","level":"充分","evidence":"虚构的提升80%","advice":"说明选择依据"},{"name":"协作","level":"充分","evidence":"虚构协作证据","advice":"继续"}],"strengths":[{"dimensionId":"d2","quote":"虚构协作证据","text":"虚构的优势"}],"gaps":[{"dimensionId":"d1","quote":"虚构的提升80%","text":"虚构的劣势"}],"improvedAnswer":"我提升了80%","practice":["继续"]}
                    """);
                default -> throw new AssertionError("Unexpected action");
            };
        });
        history=json.createArrayNode();
    }
    ObjectNode request(String action) {
        ObjectNode r=json.createObjectNode().put("action",action);
        r.set("config",config);r.set("history",history);if(state!=null)r.set("workflow",state);return r;
    }
    void start() {
        JsonNode result=workflow.run(request("plan"));
        state=json.createObjectNode();state.set("plan",result.path("plan"));state.putArray("turns").add(result.path("turn"));state.putArray("analyses");
        history.addObject().put("role","interviewer").put("text",result.at("/turn/question").asText());
    }
    JsonNode answer(String answer) {
        history.addObject().put("role","candidate").put("text",answer);
        JsonNode result=workflow.run(request("step"));
        ((ArrayNode)state.get("analyses")).add(result.path("analysis"));
        if(result.has("turn")) {
            ((ArrayNode)state.get("turns")).add(result.path("turn"));
            history.addObject().put("role","interviewer").put("text",result.at("/turn/question").asText());
        }
        return result;
    }
    @Test void vagueAnswersStillCoverAllThreeDimensionsWithinFourRounds() {
        start();
        assertEquals("d1",answer("我们做了很多优化，效果很好。").at("/turn/dimensionId").asText());
        JsonNode second=answer("具体都是团队做的。");
        assertEquals("coverage_budget",second.path("reason").asText());assertEquals("d2",second.at("/turn/dimensionId").asText());
        assertEquals("d3",answer("大家一起解决。").at("/turn/dimensionId").asText());
        assertTrue(answer("项目成功上线了。").path("done").asBoolean());
        assertEquals(4,state.path("analyses").size());assertEquals(5,calls); // last answer is analyzed too
    }
    @Test void atMostTwoFollowupsAndNoDuplicateQuestions() {
        config.put("rounds",8);start();
        answer("没有细节");answer("没有细节");
        JsonNode third=answer("没有细节");
        assertEquals("followup_limit",third.path("reason").asText());assertEquals("d2",third.at("/turn/dimensionId").asText());
        Set<String> unique=new HashSet<>();for(JsonNode t:state.path("turns"))assertTrue(unique.add(InterviewWorkflow.normalize(t.path("question").asText())));
    }
    @Test void followupUsesEvidenceAcrossAnswersInsteadOfAskingForContextAgain() throws Exception {
        config.put("rounds",8);
        assessment=json.readTree("{\"evidence\":[{\"aspect\":\"context\",\"quote\":\"目标是减少报警\"},{\"aspect\":\"action\",\"quote\":\"我添加了规则\"}],\"followupQuestion\":\"你如何判断效果？\"}");
        start();assertEquals("d1",answer("目标是减少报警，我添加了规则。").at("/turn/dimensionId").asText());
        assessment=json.readTree("{\"evidence\":[{\"aspect\":\"decision\",\"quote\":\"为了减少延迟选择批量处理\"},{\"aspect\":\"result\",\"quote\":\"报警从100条降到20条\"}],\"followupQuestion\":\"当时的背景是什么？\"}");
        JsonNode next=answer("为了减少延迟选择批量处理，报警从100条降到20条。");
        assertEquals("d2",next.at("/turn/dimensionId").asText());assertEquals("evidence_complete",next.path("reason").asText());
    }
    @Test void completeEvidenceMovesTopicsAndFinishesWithoutFiller() throws Exception {
        JsonNode full=fixtures().get(1);assessment=json.createObjectNode().set("evidence",full.path("evidence"));((ObjectNode)assessment).put("followupQuestion","");
        config.put("rounds",8);start();
        assertEquals("d2",answer(full.path("answer").asText()).at("/turn/dimensionId").asText());
        assertEquals("d3",answer(full.path("answer").asText()).at("/turn/dimensionId").asText());
        JsonNode last=answer(full.path("answer").asText());assertTrue(last.path("done").asBoolean());assertEquals("plan_complete",last.path("reason").asText());
        assertEquals(3,state.path("analyses").size());
    }
    JsonNode fixtures() throws Exception {try(var in=getClass().getResourceAsStream("/interview-regressions.json")){return json.readTree(in);}}
    @Test void fixedEvidenceFixturesRejectInventedAndSplicedQuotes() throws Exception {
        for(JsonNode f:fixtures()) {
            ArrayNode verified=workflow.verifiedEvidence(f.path("evidence"),f.path("answer").asText());
            assertEquals(f.path("expectedEvidence").asInt(),verified.size(),f.path("name").asText());
        }
    }
    @Test void earlyReviewUsesVerifiedQuotesAndMarksUnseenDimensions() throws Exception {
        assessment=json.readTree("{\"evidence\":[{\"aspect\":\"action\",\"quote\":\"我添加了去重规则\"}],\"followupQuestion\":\"为什么这么选？\"}");
        start();answer("我添加了去重规则");
        JsonNode report=workflow.run(request("review_v2"));
        assertEquals(3,report.path("dimensions").size());
        assertEquals("部分",report.at("/dimensions/0/level").asText());
        assertTrue(report.at("/dimensions/0/evidence").asText().contains("第 1 轮：「我添加了去重规则」"));
        assertEquals("未观察",report.at("/dimensions/1/level").asText());
        assertEquals("本次尚未考察。",report.at("/dimensions/2/evidence").asText());
        assertFalse(report.path("dimensions").toString().contains("80%"));
        assertTrue(report.path("strengths").isEmpty());assertTrue(report.path("gaps").isEmpty());
    }
    @Test void noEvidenceSuppressesUngroundedPraiseAndRewrites() {
        start();answer("不知道");JsonNode report=workflow.run(request("review_v2"));
        assertTrue(report.path("strengths").isEmpty());assertTrue(report.path("gaps").isEmpty());
        assertFalse(report.toString().contains("80%"));assertFalse(report.toString().contains("表现很好"));
    }
    @Test void replayingPreviousStateIsDeterministicAndDoesNotDuplicateAnswers() {
        start();history.addObject().put("role","candidate").put("text","没有细节");
        JsonNode first=workflow.run(request("step")), retry=workflow.run(request("step"));
        assertEquals(first,retry);assertEquals(0,state.path("analyses").size());
        assertEquals(2,history.size()); // server cannot append to caller state
    }
    @Test void realisticModeDoesNotReturnCoachFeedback() {
        config.put("style","realistic");start();JsonNode next=answer("没有细节");
        for(JsonNode field:next.at("/turn/feedback"))assertEquals("",field.asText());
    }
    @Test void fabricatedLedgerAndMissingAnalysisAreRejectedBeforeModelCall() {
        start();answer("没有细节");int before=calls;
        ((ArrayNode)state.at("/analyses/0/evidence")).addObject().put("aspect","result").put("quote","提高80%");
        assertThrows(AppError.class,()->workflow.run(request("review_v2")));assertEquals(before,calls);
        ((ArrayNode)state.get("analyses")).removeAll();
        assertThrows(AppError.class,()->workflow.run(request("review_v2")));assertEquals(before,calls);
    }
    @Test void invalidPlanAndDirectInternalActionsAreRejected() {
        ((ObjectNode)plan.path("dimensions").get(1)).put("question",plan.at("/dimensions/0/question").asText());
        assertEquals(502,assertThrows(AppError.class,this::start).status);
        assertThrows(AppError.class,()->workflow.run(request("assess")));
    }
    @Test void followingQuestionCannotReuseFuturePlannedQuestion() throws Exception {
        assessment=json.readTree("{\"evidence\":[],\"followupQuestion\":\"请讲一次解决团队分歧的经历。\"}");
        start();answer("没有细节");JsonNode next=answer("还是没有细节");
        assertEquals("d2",next.at("/turn/dimensionId").asText());
        Set<String> questions=new HashSet<>();for(JsonNode t:state.path("turns"))assertTrue(questions.add(t.path("question").asText()));
    }
}
