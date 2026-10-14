package com.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import java.util.*;

/** Stateless orchestration. The browser owns its practice record; counters are derived from history. */
@Service
class InterviewWorkflow {
    static final List<String> ASPECTS = List.of("context", "action", "decision", "result");
    private final ObjectMapper json;
    private final Contracts contracts;
    private final AgentService agent;

    InterviewWorkflow(ObjectMapper json, Contracts contracts, AgentService agent) {
        this.json = json; this.contracts = contracts; this.agent = agent;
    }

    JsonNode run(JsonNode raw) {
        String action = raw == null ? "" : raw.path("action").asText();
        if (List.of("prepare", "turn", "review").contains(action)) return agent.run(contracts.validate(raw));
        if (!List.of("plan", "step", "review_v2").contains(action)) throw new AppError("不支持的操作。");
        ObjectNode basic = raw.deepCopy();
        basic.put("action", action.equals("plan") ? "prepare" : "review");
        ObjectNode input = contracts.validate(basic);
        JsonNode history = input.path("history");
        int answered = history.size() / 2;
        int rounds = input.at("/config/rounds").asInt();
        if (history.size() > rounds * 2) throw new AppError("对话超过设定轮数。");
        if (action.equals("plan")) {
            input.put("action", "plan");
            JsonNode plan = agent.run(input);
            for (JsonNode d:plan.path("dimensions")) for (String key:List.of("title","goal","question"))
                if(d.path(key).isTextual()) ((ObjectNode)d).put(key,d.path(key).asText().strip());
            validatePlan(plan, 502);
            ObjectNode result = json.createObjectNode();
            result.set("plan", plan);
            result.set("turn", turn(plan.path("dimensions").get(0), "opening", "", "plan", input));
            return result;
        }
        if (action.equals("step") && history.size() % 2 != 0) throw new AppError("请先提交当前问题的回答。");
        JsonNode state = raw.path("workflow");
        validateState(state, history, answered - (action.equals("step") ? 1 : 0));
        input.set("workflow", state);
        if (action.equals("review_v2")) return review(input);

        JsonNode lastTurn = state.path("turns").get(state.path("turns").size() - 1);
        JsonNode dimension = dimension(state.path("plan"), lastTurn.path("dimensionId").asText());
        input.put("action", "assess");
        input.set("currentDimension", dimension);
        JsonNode assessment = agent.run(input);
        ArrayNode evidence = verifiedEvidence(assessment.path("evidence"), history.get(history.size()-1).path("text").asText());
        ObjectNode analysis = json.createObjectNode().put("answerIndex", answered).put("dimensionId", dimension.path("id").asText());
        analysis.set("evidence", evidence);
        analysis.set("missing", missing(evidence));
        ObjectNode result = json.createObjectNode();
        result.set("analysis", analysis);
        if (answered >= rounds) return result.put("done", true).put("reason", "round_limit");

        JsonNode dimensions = state.path("plan").path("dimensions");
        Set<String> visited = new HashSet<>();
        for (JsonNode t : state.path("turns")) visited.add(t.path("dimensionId").asText());
        List<JsonNode> unvisited = new ArrayList<>();
        for (JsonNode d : dimensions) if (!visited.contains(d.path("id").asText())) unvisited.add(d);
        int consecutive = 0;
        for (int i = state.path("turns").size()-1; i >= 0; i--) {
            if (!state.path("turns").get(i).path("dimensionId").equals(dimension.path("id"))) break;
            consecutive++;
        }
        // Reserve one question for each unseen dimension, even if the answer remains vague.
        ArrayNode dimensionEvidence=json.createArrayNode();
        for (JsonNode previous:state.path("analyses")) if(previous.path("dimensionId").equals(dimension.path("id")))
            for (JsonNode e:previous.path("evidence")) dimensionEvidence.add(e);
        dimensionEvidence.addAll(evidence);
        ArrayNode dimensionGaps=missing(dimensionEvidence);
        boolean followup = !dimensionGaps.isEmpty() && consecutive < 3 && rounds-answered > unvisited.size();
        String reason;
        JsonNode nextDimension;
        String question = "";
        if (followup) {
            nextDimension = dimension;
            question = assessment.path("followupQuestion").asText().strip();
            boolean plannedElsewhere=false;
            for(JsonNode d:dimensions) if(!d.path("id").equals(dimension.path("id")) && normalize(question).equals(normalize(d.path("question").asText()))) plannedElsewhere=true;
            if (question.isBlank() || question.length()>1000 || repeated(question, history) || plannedElsewhere)
                question = fallbackQuestion(dimension, dimensionGaps, history, input);
            reason = "evidence_gap";
        } else {
            // Once every dimension has been asked, ending early is preferable to filling spare rounds.
            if (unvisited.isEmpty()) return result.put("done", true).put("reason", "plan_complete");
            nextDimension = unvisited.get(0);
            reason = consecutive >= 3 ? "followup_limit" : rounds-answered <= unvisited.size() ? "coverage_budget" : "evidence_complete";
        }
        ObjectNode next = turn(nextDimension, followup ? "followup" : "new_topic", question, reason, input);
        if (repeated(next.path("question").asText(), history)) {
            // A generated follow-up can coincide with a future planned opening question.
            next.put("question", fallbackQuestion(nextDimension, missing(json.createArrayNode()), history, input));
        }
        if (input.at("/config/style").asText().equals("coach")) next.set("feedback", feedback(analysis, input));
        result.put("done", false).put("reason", reason).set("turn", next);
        return result;
    }

    private void validatePlan(JsonNode plan, int status) {
        if (!contracts.validOutput(plan, contracts.schemas.get("plan")) || plan.path("dimensions").size()!=3)
            throw new AppError("面试计划需要三个完整维度，请重试。", status);
        Set<String> questions = new HashSet<>(), titles = new HashSet<>();
        for (int i=0; i<3; i++) {
            JsonNode d = plan.path("dimensions").get(i);
            if (!d.path("id").asText().equals("d"+(i+1))) throw new AppError("面试计划维度标识不正确。",status);
            for (String key : List.of("title", "goal", "question"))
                if (d.path(key).asText().isBlank() || d.path(key).asText().length()>1000) throw new AppError("面试计划字段为空或过长。",status);
            if (!questions.add(normalize(d.path("question").asText())) || !titles.add(normalize(d.path("title").asText())))
                throw new AppError("面试计划包含重复主题或问题，请重试。",status);
        }
    }

    private void validateState(JsonNode state, JsonNode history, int expectedAnalyses) {
        validatePlan(state.path("plan"),400);
        JsonNode turns = state.path("turns"), analyses = state.path("analyses");
        if (!turns.isArray() || turns.size()!=(history.size()+1)/2 || !analyses.isArray() || analyses.size()!=expectedAnalyses)
            throw new AppError("面试进度与对话不一致，请刷新后重试。");
        for (int i=0; i<turns.size(); i++) {
            JsonNode t=turns.get(i);
            dimension(state.path("plan"), t.path("dimensionId").asText());
            if (!t.path("question").equals(history.get(i*2).path("text")) || !List.of("opening","followup","new_topic").contains(t.path("kind").asText()))
                throw new AppError("问题记录与面试进度不一致。");
        }
        for (int i=0; i<analyses.size(); i++) {
            JsonNode a = analyses.get(i);
            if (!a.path("answerIndex").isIntegralNumber() || a.path("answerIndex").asInt()!=i+1 || !a.path("dimensionId").equals(turns.get(i).path("dimensionId")))
                throw new AppError("回答分析顺序不正确。");
            ArrayNode verified = verifiedEvidence(a.path("evidence"), history.get(i*2+1).path("text").asText());
            if (!verified.equals(a.path("evidence")) || !missing(verified).equals(a.path("missing")))
                throw new AppError("回答证据与原文不一致。");
        }
    }

    JsonNode dimension(JsonNode plan, String id) {
        for (JsonNode d : plan.path("dimensions")) if (d.path("id").asText().equals(id)) return d;
        throw new AppError("未知的面试维度。");
    }

    ArrayNode verifiedEvidence(JsonNode source, String answer) {
        ArrayNode result = json.createArrayNode(); Set<String> seen = new HashSet<>();
        if (!source.isArray()) return result;
        for (JsonNode e : source) {
            String aspect=e.path("aspect").asText(), quote=e.path("quote").asText();
            if (ASPECTS.contains(aspect) && !quote.isBlank() && quote.length()<=1000 && answer.contains(quote) && seen.add(aspect))
                result.addObject().put("aspect",aspect).put("quote",quote);
        }
        return result;
    }
    ArrayNode missing(JsonNode evidence) {
        Set<String> found = new HashSet<>(); for (JsonNode e : evidence) found.add(e.path("aspect").asText());
        ArrayNode result=json.createArrayNode(); for (String a : ASPECTS) if (!found.contains(a)) result.add(a); return result;
    }
    static String normalize(String text) { return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    static boolean repeated(String question, JsonNode history) {
        for (JsonNode h : history) if (h.path("role").asText().equals("interviewer") && normalize(h.path("text").asText()).equals(normalize(question))) return true;
        return false;
    }
    private String fallbackQuestion(JsonNode d, JsonNode gaps, JsonNode history, JsonNode input) {
        boolean en=input.at("/config/language").asText().equals("en");
        Map<String,String> zh=Map.of("context","当时要解决的具体问题是什么", "action","你本人具体采取了什么行动", "decision","你为什么选择这个方案而没有采用其他方案", "result","有什么可核实的结果可以说明成效");
        Map<String,String> english=Map.of("context","what specific problem needed to be solved", "action","what specific action did you personally take", "decision","why did you choose this approach over the alternatives", "result","what observable evidence shows the outcome");
        for (JsonNode gap : gaps) {
            String q = en ? "Regarding “"+d.path("title").asText()+"”, "+english.get(gap.asText())+"?" : "关于「"+d.path("title").asText()+"」，"+zh.get(gap.asText())+"？";
            if (!repeated(q,history)) return q;
        }
        // Bounded alternative when all standard prompts have already appeared in a long answer chain.
        String q=en ? "For “"+d.path("title").asText()+"”, which detail in your most recent example best demonstrates your own contribution?" : "关于「"+d.path("title").asText()+"」，你刚才的例子中哪个具体细节最能说明你的个人贡献？";
        if (repeated(q,history)) throw new AppError("未能生成不重复的问题，请重试或提前复盘。",502);
        return q;
    }
    private ObjectNode turn(JsonNode d, String kind, String question, String reason, JsonNode input) {
        ObjectNode t=json.createObjectNode().put("dimensionId",d.path("id").asText()).put("focus",d.path("title").asText())
            .put("kind",kind).put("question",question.isBlank()?d.path("question").asText():question).put("reason",reason);
        t.set("feedback",json.valueToTree(Map.of("evidence","","strength","","gap","","nextStep","")));
        return t;
    }
    private ObjectNode feedback(JsonNode analysis, JsonNode input) {
        boolean en=input.at("/config/language").asText().equals("en");
        List<String> quotes=new ArrayList<>(); for (JsonNode e:analysis.path("evidence")) quotes.add(e.path("quote").asText());
        String gap=gapLabels(analysis.path("missing"),en);
        return json.createObjectNode().put("evidence",quotes.isEmpty()?(en?"No specific evidence extracted from this answer.":"这次回答尚未提取到具体证据。"):String.join(" / ",quotes))
            .put("strength",quotes.isEmpty()?(en?"Not enough evidence to assess.":"暂无足够依据评价。"):(en?"The quoted details can support a more concrete account.":"已提供上述可引用细节，可据此继续展开。"))
            .put("gap",gap.isEmpty()?(en?"All four evidence categories have been mentioned.":"背景、个人行动、取舍、结果四类证据均已涉及。"):gap)
            .put("nextStep",gap.isEmpty()?(en?"Check that each detail is accurate and relevant.":"检查细节是否准确，并突出与岗位要求的联系。"):(en?"Add a real detail for the missing categories; do not invent facts.":"补充缺失部分的真实细节；没有的数据不必编造。"));
    }
    private String gapLabels(JsonNode missing, boolean en) {
        Map<String,String> labels=Map.of("context", "背景与目标", "action", "个人行动", "decision", "决策取舍", "result", "结果依据");
        List<String> result=new ArrayList<>(); for (JsonNode g:missing) result.add(en?g.asText():labels.get(g.asText())); return String.join("、",result);
    }
    private JsonNode review(ObjectNode input) {
        input.put("action","reflect");
        ObjectNode report=(ObjectNode)agent.run(input);
        ArrayNode dimensions=json.createArrayNode();
        boolean en=input.at("/config/language").asText().equals("en");
        for (JsonNode d : input.at("/workflow/plan/dimensions")) {
            ArrayNode evidence=json.createArrayNode(); List<String> citations=new ArrayList<>();
            boolean asked=false;
            for (JsonNode a:input.at("/workflow/analyses")) if (a.path("dimensionId").equals(d.path("id"))) {
                asked=true;
                for (JsonNode e:a.path("evidence")) { evidence.add(e); citations.add((en?"Answer ":"第 ")+a.path("answerIndex").asInt()+(en?": “":" 轮：「")+e.path("quote").asText()+(en?"”":"」")); }
            }
            JsonNode gaps=missing(evidence);
            String level=evidence.isEmpty()?"未观察":gaps.isEmpty()?"充分":"部分";
            String advice="";
            if (!evidence.isEmpty()) for (JsonNode proposed:report.path("dimensions")) if (proposed.path("name").equals(d.path("title"))) { advice=proposed.path("advice").asText(); break; }
            if (advice.isBlank()) advice=evidence.isEmpty()?(en?"Practice this dimension with a concrete example.":"下一次用一个具体经历补充这个维度。"):gaps.isEmpty()?(en?"Practice a concise account of the evidence above.":"练习用更简洁的表达串联上述证据。"):(en?"Add evidence for: ":"继续补充：")+gapLabels(gaps,en);
            dimensions.addObject().put("name",d.path("title").asText()).put("level",level)
                .put("evidence",citations.isEmpty()?(asked?(en?"Asked, but no specific evidence was extracted.":"已提问，但未提取到具体证据。"):(en?"Not covered in this session.":"本次尚未考察。")):String.join("\n",citations)).put("advice",advice);
        }
        report.set("dimensions",dimensions);
        int observed=0; for (JsonNode d:dimensions) if (!d.path("level").asText().equals("未观察")) observed++;
        report.put("summary",en?"This session collected specific evidence for "+observed+" of 3 planned dimensions. Review the cited details and practice the missing categories.":"本场三个考察维度中，"+observed+" 个维度已收集到具体证据。请结合下方原话检查表达，并补充未覆盖的信息。");
        for (String field:List.of("strengths","gaps")) {
            ArrayNode claims=json.createArrayNode();
            for (JsonNode claim:report.path(field)) {
                boolean matched=false;
                for (JsonNode a:input.at("/workflow/analyses")) {
                    if (!a.path("dimensionId").equals(claim.path("dimensionId"))) continue;
                    for (JsonNode e:a.path("evidence")) if (e.path("quote").equals(claim.path("quote")) && !claim.path("text").asText().isBlank()) {
                        claims.add(claim.path("text").asText()+(en?" — Answer ":" —— 第 ")+a.path("answerIndex").asInt()+(en?": “":" 轮：「")+e.path("quote").asText()+(en?"”":"」"));matched=true;break;
                    }
                    if(matched)break;
                }
            }
            report.set(field,claims);
        }
        report.put("evidenceNote",en?"Levels describe coverage of context, personal action, decisions and outcomes in this practice, not hiring ability. Quote matching does not verify truth or interpretation.":"等级表示本次回答对背景、个人行动、取舍、结果的证据覆盖程度，不代表能力评级。原文校验不验证事实真伪或模型理解是否准确。");
        // With no valid evidence, suppress model-authored praise and criticism entirely.
        boolean any=false; for (JsonNode a:input.at("/workflow/analyses")) if (!a.path("evidence").isEmpty()) any=true;
        if (!any) {
            report.put("summary",en?"There is not enough evidence in this practice to assess performance.":"本次练习尚无足够具体证据，暂不评价表现。");
            report.set("strengths",json.createArrayNode()); report.set("gaps",json.createArrayNode());
            report.put("improvedAnswer",en?"Context: [add facts]\nMy action: [add facts]\nDecision: [add facts]\nOutcome: [add facts]":"背景：[待补充]\n我的行动：[待补充]\n取舍：[待补充]\n结果：[待补充]");
            report.set("practice",json.valueToTree(List.of(en?"Prepare one concrete example and describe your own action and outcome.":"准备一个具体经历，说明你本人的行动及结果依据。")));
        }
        return report;
    }
}
