package com.bai.scene.web;

import com.bai.common.BaiException;
import com.bai.common.BaiResponse;
import com.bai.scene.core.SceneContextStore;
import com.bai.scene.core.SceneExecutor;
import com.bai.scene.llm.LlmSceneRouter;
import com.bai.scene.llm.SceneDialogSpecs;
import com.bai.scene.llm.SceneLlmDialogService;
import com.bai.scene.script.SceneScript;
import com.bai.scene.script.SceneScripts;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.spatix.scaff.iagent.model.StepState;
import com.spatix.scaff.iagent.store.SessionStore;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 场景对话入口（/api/scene/**）：
 * <ul>
 *   <li>POST /api/scene/chat —— 一句话意图路由 → 创建会话 → 执行剧本处理链</li>
 *   <li>POST /api/scene/confirm —— 人工确认节点自然语言回复（1/2/3）</li>
 *   <li>GET /api/scene/sessions/{id} —— 会话状态与步骤视图</li>
 *   <li>GET /api/scene/scripts —— 已注册场景清单（含开场引导）</li>
 * </ul>
 * SSE 滚动日志由 scaff-iagent 契约提供：/api/iagent/sessions/{id}/events 与 /logs。
 */
@RestController
@RequestMapping("/api/scene")
public class SceneController {

    private final SceneExecutor executor;
    private final SessionStore store;
    private final SceneContextStore contextStore;
    private final SceneLlmDialogService dialog;
    private final LlmSceneRouter sceneRouter;

    public SceneController(SceneExecutor executor, SessionStore store, SceneContextStore contextStore,
                           SceneLlmDialogService dialog, LlmSceneRouter sceneRouter) {
        this.executor = executor;
        this.store = store;
        this.contextStore = contextStore;
        this.dialog = dialog;
        this.sceneRouter = sceneRouter;
    }

    public record ChatRequest(String userId, String message) {}
    public record ConfirmRequest(String sessionId, String reply) {}

    /** 清除对话层残留状态：前端「新建对话」时调用，防止旧场景槽位收集吞掉下一条消息（消息被旧场景接管、跳过意图路由） */
    @PostMapping("/dialog/reset")
    public BaiResponse<Map<String, Object>> resetDialog(@RequestBody ChatRequest req,
                                                        @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String userId = req.userId() == null || req.userId().isBlank() ? "demo-user" : req.userId();
        boolean cleared = dialog.reset(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("userId", userId);
        data.put("cleared", cleared);
        return BaiResponse.ok(reqId(requestId), data);
    }

    @PostMapping("/chat")
    public BaiResponse<Map<String, Object>> chat(@RequestBody ChatRequest req,
                                                 @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String userId = req.userId() == null || req.userId().isBlank() ? "demo-user" : req.userId();

        // 大模型对话层（SceneDialogSpecs 注册的场景全对话 LLM 化；LLM 关闭/未命中/失败自动回退关键词路由）
        SceneLlmDialogService.LlmTurn turn = dialog.handle(userId, req.message());
        if (turn != null && turn.sceneId() == null && turn.reply() != null && !turn.reply().isBlank()
                && !turn.routeOther()) {
            // 资料内容问答直达：用户追问上传资料内容，对话层已依据解析结果直接作答，无需场景路由
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", null);
            data.put("reply", turn.reply());
            data.put("status", "DIALOG");
            data.put("blocks", List.of());
            return BaiResponse.ok(reqId(requestId), data);
        }
        if (turn != null && turn.sceneId() != null && !turn.routeOther()) {
            SceneScript script = SceneScripts.get(turn.sceneId());
            if (turn.slots() != null) {
                // 要素齐备：终止修改前的旧会话（若有），按新槽位启动对应场景剧本
                String prev = dialog.lastSession(userId);
                if (prev != null) {
                    SceneContextStore.SceneContext old = contextStore.find(prev);
                    if (old != null && old.pendingConfirmStepId != null
                            && old.status == SceneContextStore.Status.AWAITING_CONFIRM) {
                        executor.confirmResume(prev, old.pendingConfirmStepId, false);
                    }
                }
                Map<String, Object> slots = new HashMap<>(turn.slots());
                slots.put("message", req.message());
                SceneContextStore.SceneContext ctx = executor.start(script, userId, slots);
                dialog.markSession(userId, ctx.sessionId);
                boolean done = ctx.status == SceneContextStore.Status.COMPLETED;
                // 无人工确认节点的场景：启动即执行完毕，直接生成解读并进入追问模式
                if (done && SceneDialogSpecs.has(script.sceneId())) {
                    java.util.List<String> lines = dialog.interpret(ctx);
                    if (lines != null && !lines.isEmpty()) {
                        java.util.List<Map<String, Object>> withLlm = new ArrayList<>(ctx.blocks);
                        withLlm.add(Map.of("title", "智能解读（大模型基于实算结果生成）", "lines", lines));
                        ctx.blocks = withLlm;
                    }
                    dialog.retypeBlocks(ctx);
                    dialog.finish(ctx.userId, ctx);
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sessionId", ctx.sessionId);
                data.put("sceneId", script.sceneId());
                data.put("agentId", script.agentId());
                data.put("title", script.title());
                String reply = turn.reply() == null ? "" : turn.reply().replaceAll("。+$", "");
                data.put("reply", entryTag(script, "LLM对话") + "\n"
                        + reply + (done
                            ? "。已按您确认的要素执行完毕，结果如下。"
                            : "。处理链已执行到人工确认节点，回复 1 确认执行，2 修改要素，3 暂不。"));
                if (done) {
                    // 追问引导独立成 hint 字段：前端在结果分块之后渲染，避免建议句出现在结果内容之前
                    data.put("hint", "您可以继续就本次结果追问，或直接输入新指令发起新场景。");
                }
                data.put("status", ctx.status.name());
                data.put("blocks", ctx.blocks);
                return BaiResponse.ok(reqId(requestId), data);
            }
            // 槽位收集中：LLM 追问/澄清轮次，尚未建会话；附带提示词库开场引导（前端每场景首轮渲染一次）
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", null);
            data.put("sceneId", script.sceneId());
            data.put("opening", script.opening());
            data.put("reply", entryTag(script, "对话中") + "\n" + turn.reply());
            data.put("status", "DIALOG");
            data.put("blocks", List.of());
            // 存在待确认会话时保持待确认 UI（CONFIRM_TURN 轮次），避免状态条消失
            String live = dialog.lastSession(userId);
            if (live != null) {
                SceneContextStore.SceneContext pending = contextStore.find(live);
                if (pending != null && pending.status == SceneContextStore.Status.AWAITING_CONFIRM) {
                    data.put("sessionId", live);
                    data.put("status", "AWAITING_CONFIRM");
                    data.put("blocks", pending.blocks);
                }
            }
            return BaiResponse.ok(reqId(requestId), data);
        }

        // 两段式 LLM 意图路由（S10-1 全渠道统一入口职责）：粗排召回→LLM精判→置信度门控；
        // 未命中/超时/异常一律静默降级到关键词路由，行为与已验证链路完全兼容；
        // 低置信且有关澄清话术时返回澄清反问，不硬选场景
        LlmSceneRouter.RouteResult routed = sceneRouter.route(req.message());
        String llmSceneId = routed != null ? routed.sceneId() : null;
        SceneScript script = llmSceneId != null ? SceneScripts.get(llmSceneId) : null;
        if (script == null) {
            script = SceneScripts.match(req.message());
        }
        if (script == null) {
            if (routed != null && routed.clarify() != null) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sessionId", null);
                data.put("reply", "【意图澄清】\n" + routed.clarify());
                data.put("blocks", List.of());
                return BaiResponse.ok(reqId(requestId), data);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("sessionId", null);
            data.put("reply", "【未命中场景】\n暂未识别到营销获客场景诉求。当前支持："
                    + String.join("、", SceneScripts.all().values().stream().map(SceneScript::title).toList())
                    + "。您可以例如说“把名下房贷90天内到期的客户筛出来，推荐经营贷，发短信触达”。");
            data.put("blocks", List.of());
            return BaiResponse.ok(reqId(requestId), data);
        }
        Map<String, Object> slots = new HashMap<>();
        slots.put("message", req.message());
        int limit = SceneScripts.extractLimit(req.message());
        if (limit > 0) slots.put("limit", limit);
        if ("S1-7".equals(script.sceneId()) && (req.message().contains("纪要") || req.message().contains("拜访"))) {
            slots.put("memoText", req.message());
        }
        SceneContextStore.SceneContext ctx = executor.start(script, userId, slots);
        dialog.markSession(userId, ctx.sessionId);
        // 与对话层分支对齐：无人工确认节点、启动即完成的场景同样生成智能解读（失败静默跳过）
        if (ctx.status == SceneContextStore.Status.COMPLETED && SceneDialogSpecs.has(script.sceneId())) {
            java.util.List<String> lines = dialog.interpret(ctx);
            if (lines != null && !lines.isEmpty()) {
                java.util.List<Map<String, Object>> withLlm = new ArrayList<>(ctx.blocks);
                withLlm.add(Map.of("title", "智能解读（大模型基于实算结果生成）", "lines", lines));
                ctx.blocks = withLlm;
            }
            dialog.retypeBlocks(ctx);
            dialog.finish(ctx.userId, ctx);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", ctx.sessionId);
        data.put("sceneId", script.sceneId());
        data.put("agentId", script.agentId());
        data.put("title", script.title());
        String routeVia = llmSceneId != null ? "LLM路由" : "关键词路由";
        data.put("reply", entryTag(script, routeVia) + "\n"
                + buildReply(script, ctx));
        data.put("status", ctx.status.name());
        data.put("blocks", ctx.blocks);
        return BaiResponse.ok(reqId(requestId), data);
    }

    @PostMapping("/confirm")
    public BaiResponse<Map<String, Object>> confirm(@RequestBody ConfirmRequest req,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        SceneContextStore.SceneContext ctx = contextStore.find(req.sessionId());
        Map<String, Object> data = new LinkedHashMap<>();
        if (ctx == null) {
            data.put("reply", "会话不存在或已结束，请重新发起诉求。");
            data.put("blocks", List.of());
            return BaiResponse.ok(reqId(requestId), data);
        }
        String reply = req.reply() == null ? "" : req.reply().trim();
        SceneScript script = ctx.script;
        data.put("sceneId", script.sceneId());
        if (ctx.pendingConfirmStepId == null) {
            // 会话不在挂起点（已执行完毕或已终止），任何回复都做提示性引导
            data.put("reply", ctx.status == SceneContextStore.Status.COMPLETED
                    ? "该场景处理链已执行完毕，无待确认事项。"
                    : "当前会话无待确认事项，请重新发起诉求。");
            if (ctx.status == SceneContextStore.Status.COMPLETED) {
                data.put("hint", nextGuide(script));
            }
            data.put("status", ctx.status.name());
            data.put("blocks", ctx.blocks);
            return BaiResponse.ok(reqId(requestId), data);
        }
        if (reply.startsWith("1") || reply.contains("确认")) {
            executor.confirmResume(ctx.sessionId, ctx.pendingConfirmStepId, true);
            if (ctx.status == SceneContextStore.Status.COMPLETED) {
                // 全对话 LLM 化场景：执行完成后由大模型基于真实结果数据生成个性化解读（失败静默跳过）
                if (SceneDialogSpecs.has(script.sceneId())) {
                    java.util.List<String> lines = dialog.interpret(ctx);
                    if (lines != null && !lines.isEmpty()) {
                        java.util.List<Map<String, Object>> withLlm = new ArrayList<>(ctx.blocks);
                        withLlm.add(Map.of("title", "智能解读（大模型基于实算结果生成）", "lines", lines));
                        ctx.blocks = withLlm;
                    }
                    dialog.retypeBlocks(ctx);
                    // 完成后进入「追问」模式：保留结果上下文，用户可继续提问或改要素重跑
                    dialog.finish(ctx.userId, ctx);
                }
                // 追问引导独立成 hint 字段：前端在结果分块之后渲染，避免建议句出现在结果内容之前
                data.put("reply", "已按您的确认执行完毕，结果如下。");
                data.put("hint", SceneDialogSpecs.has(script.sceneId())
                        ? "您可以继续就本次结果追问" + SceneDialogSpecs.get(script.sceneId()).followupHint
                          + "，或直接输入新指令发起新场景。"
                        : nextGuide(script));
            } else {
                data.put("reply", entryTag(script, "确认执行") + "\n" + buildReply(script, ctx));
            }
            data.put("status", ctx.status.name());
            data.put("blocks", ctx.blocks);
        } else if (reply.startsWith("2") || reply.contains("修改")) {
            if (SceneDialogSpecs.has(script.sceneId())) {
                // LLM 修改引导：进入多轮要素调整对话，新要素就绪后自动终止旧会话并重跑
                String ask = dialog.beginModify(ctx.userId, ctx.slots, script.sceneId());
                data.put("reply", ask);
            } else {
                data.put("reply", "好的，请直接说明要调整的内容（如客群条件、权益、条数、渠道），我会按新口径重新生成方案。当前方案暂不执行。");
            }
            data.put("status", ctx.status.name());
            data.put("blocks", ctx.blocks);
        } else if (reply.startsWith("3") || reply.contains("取消") || reply.contains("暂不") || reply.contains("暂停")
                || reply.contains("结束")) {
            executor.confirmResume(ctx.sessionId, ctx.pendingConfirmStepId, false);
            dialog.close(ctx.userId);
            data.put("reply", "本次流程已终止。如需重新发起，请直接说明诉求。");
            data.put("status", ctx.status.name());
            data.put("blocks", ctx.blocks);
        } else {
            data.put("reply", "请回复数字选择：" + script.confirmPrompt());
            data.put("status", ctx.status.name());
            data.put("blocks", ctx.blocks);
        }
        return BaiResponse.ok(reqId(requestId), data);
    }

    @GetMapping("/sessions/{sessionId}")
    public BaiResponse<Map<String, Object>> session(@PathVariable String sessionId,
                                                    @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        SceneContextStore.SceneContext ctx = contextStore.find(sessionId);
        Map<String, Object> data = new LinkedHashMap<>();
        if (ctx == null) {
            data.put("reply", "会话不存在");
            return BaiResponse.ok(reqId(requestId), data);
        }
        data.put("sessionId", sessionId);
        data.put("sceneId", ctx.script.sceneId());
        data.put("title", ctx.script.title());
        data.put("status", ctx.status.name());
        data.put("slots", ctx.slots);
        data.put("blocks", ctx.blocks);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (var s : store.findSteps(sessionId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("stepId", s.getStepId());
            m.put("name", s.getName());
            m.put("agentId", s.getAgentId());
            m.put("dependsOn", s.getDependsOn());
            m.put("state", s.getState().name());
            m.put("detail", s.getDetail());
            steps.add(m);
        }
        data.put("steps", steps);
        return BaiResponse.ok(reqId(requestId), data);
    }

    // ===== 文件上传区（提示词库 1.3.1/1.3.2 前端交互硬规范：中途补资料全在前端对话完成，由 doc-parse-agent 解析） =====

    /** 上传解析的 HTTP 客户端（复用项目 java.net.http 风格） */
    private final HttpClient uploadHttp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper uploadMapper = new ObjectMapper();
    /** 上传大小上限 10MB（演示口径，生产按影像平台限额调整） */
    private static final long UPLOAD_MAX_BYTES = 10L * 1024 * 1024;

    /**
     * POST /api/scene/upload —— 前端文件上传区入口：接收资料文件 → 调用 doc-parse-agent 全流程解析
     * （分类/版面/表格/KV）→ 返回解析摘要分块，由前端追加进对话流；不跳转后台、不切换表单页。
     */
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BaiResponse<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
                                                   @RequestParam(value = "sessionId", required = false) String sessionId,
                                                   @RequestParam(value = "userId", required = false) String userId,
                                                   @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        String rid = requestId == null || requestId.isBlank() ? "req-" + UUID.randomUUID() : requestId;
        if (file == null || file.isEmpty()) {
            throw new BaiException("SCN_1001", "参数缺失：请选择要上传的资料文件");
        }
        String name = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "未命名文件" : file.getOriginalFilename();
        if (file.getSize() > UPLOAD_MAX_BYTES) {
            throw new BaiException("SCN_1002", "文件过大（上限 10MB），请压缩后重新上传");
        }
        JsonNode pr = callDocParse(file, name, rid);

        // 解析摘要分块（与场景结果分块同一渲染协议，前端直接追加到对话流）
        List<String> lines = new ArrayList<>();
        lines.add("文件：" + name + "（" + humanSize(file.getSize()) + "）");
        String docType = pr.path("docType").asText("unknown");
        lines.add("类型识别：" + docTypeCn(docType) + "，置信度 "
                + Math.round(pr.path("docTypeConfidence").asDouble(0) * 100) + "%，共 "
                + pr.path("pages").asInt(1) + " 页");
        int tables = pr.path("tables").size();
        if (tables > 0) {
            lines.add("识别表格：" + tables + " 张（已结构化）");
        }
        JsonNode kv = pr.path("kv");
        if (kv.isObject() && kv.size() > 0) {
            int shown = 0;
            Iterator<String> it = kv.fieldNames();
            while (it.hasNext() && shown < 6) {
                String k = it.next();
                String v = kv.path(k).path("value").asText("");
                if (!v.isBlank()) {
                    lines.add(kvLabel(k) + "：" + v);
                    shown++;
                }
            }
            if (kv.size() > shown) {
                lines.add("其余 " + (kv.size() - shown) + " 个字段已入库（docId=" + pr.path("docId").asText("-") + "）");
            }
        }
        if (pr.path("validation").path("needReview").asBoolean(false)) {
            lines.add("存在低置信字段，建议人工复核");
        }
        lines.add("解析耗时 " + pr.path("elapsedMs").asInt(0) + " ms · 已存档 docId " + pr.path("docId").asText("-"));

        Map<String, Object> block = new LinkedHashMap<>();
        block.put("title", "资料解析（doc-parse）");
        block.put("lines", lines);

        // 方案A/B：解析摘要注入对话层上下文——LLM 可引用资料内容，槽位收集可直接提取要素（无对话时暂存，下条消息补注入）
        boolean attached = dialog.attachDoc(userId == null || userId.isBlank() ? "demo-user" : userId,
                String.join("\n", lines));
        if (attached) {
            lines.add("已注入对话上下文：可直接追问资料内容，或发起场景自动带入要素");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sessionId", sessionId);
        data.put("fileName", name);
        data.put("attached", attached);
        data.put("block", block);
        return BaiResponse.ok(rid, data);
    }

    /** 调用 doc-parse-agent（:8143 /parse，multipart）全流程解析；失败转统一错误码 */
    private JsonNode callDocParse(MultipartFile file, String name, String requestId) {
        try {
            String boundary = "----scnbff" + UUID.randomUUID().toString().replace("-", "");
            byte[] fileBytes = file.getBytes();
            var head = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + name.replace("\"", "") + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var tail = ("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] body = new byte[head.length + fileBytes.length + tail.length];
            System.arraycopy(head, 0, body, 0, head.length);
            System.arraycopy(fileBytes, 0, body, head.length, fileBytes.length);
            System.arraycopy(tail, 0, body, head.length + fileBytes.length, tail.length);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:8143/api/bai/docparse/parse"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-Request-Id", requestId)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<String> resp = uploadHttp.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = uploadMapper.readTree(resp.body());
            if (!"0".equals(root.path("code").asText(""))) {
                throw new BaiException("SCN_1003", "资料解析失败：" + root.path("message").asText("doc-parse 返回异常"));
            }
            return root.path("data");
        } catch (BaiException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new BaiException("SCN_1004", "读取文件失败：" + e.getMessage());
        } catch (Exception e) {
            throw new BaiException("SCN_1005", "连接 doc-parse-agent 失败（请确认 8143 原子服务已启动）：" + e.getMessage());
        }
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024 * 1024) {
            return String.format("%.1fMB", bytes / 1024.0 / 1024.0);
        }
        return Math.max(1, bytes / 1024) + "KB";
    }

    /** KV schema 字段名 → 演示卡中文标签（doc-parse 契约字段为英文标识，展示层翻译；未知字段回退原名） */
    private static final Map<String, String> KV_LABELS = Map.ofEntries(
            Map.entry("contract_no", "合同编号"), Map.entry("borrower_name", "借款人"),
            Map.entry("loan_amount", "贷款金额（元）"), Map.entry("loan_rate", "年利率（%）"),
            Map.entry("loan_term", "贷款期限"), Map.entry("guarantor_name", "保证人"),
            Map.entry("guarantee_amount", "担保金额（元）"), Map.entry("guarantee_way", "担保方式"),
            Map.entry("bill_no", "票据号码"), Map.entry("drawee", "出票人"), Map.entry("payee", "收款人"),
            Map.entry("payee_name", "收款人"), Map.entry("bill_amount", "票面金额（元）"),
            Map.entry("voucher_amount", "凭证金额（元）"), Map.entry("due_date", "到期日"),
            Map.entry("account_no", "账号"), Map.entry("period", "账期"),
            Map.entry("credit_total", "贷方发生额（元）"), Map.entry("debit_total", "借方发生额（元）"),
            Map.entry("cert_no", "证书编号"), Map.entry("holder_name", "持有人"),
            Map.entry("issue_org", "发证机关"), Map.entry("issue_date", "发证日期"),
            Map.entry("report_no", "报告编号"), Map.entry("subject_name", "标的/被评估单位"),
            Map.entry("report_period", "报告期间"), Map.entry("voucher_no", "凭证编号"),
            Map.entry("voucher_date", "凭证日期"));

    private static String kvLabel(String k) {
        return KV_LABELS.getOrDefault(k, k);
    }

    private static String docTypeCn(String t) {
        return switch (t == null ? "" : t) {
            case "invoice" -> "发票";
            case "id_card" -> "身份证";
            case "bank_statement" -> "银行流水";
            case "loan_contract" -> "贷款合同";
            case "guarantee_contract" -> "担保合同";
            case "bill" -> "票据";
            case "certificate" -> "证照";
            case "report" -> "评估报告";
            case "voucher" -> "凭证";
            case "contract" -> "合同";
            case "" -> "未识别";
            default -> t;
        };
    }

    @GetMapping("/scripts")
    public BaiResponse<List<Map<String, Object>>> scripts(
            @RequestHeader(value = "X-Request-Id", required = false) String requestId) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (SceneScript s : SceneScripts.all().values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sceneId", s.sceneId());
            m.put("agentId", s.agentId());
            m.put("title", s.title());
            m.put("opening", s.opening());
            m.put("keywords", s.keywords());
            list.add(m);
        }
        return BaiResponse.ok(reqId(requestId), list);
    }

    @GetMapping("/health")
    public BaiResponse<String> health() {
        return BaiResponse.ok("health", "UP");
    }

    // ---------- internal ----------

    private String buildReply(SceneScript script, SceneContextStore.SceneContext ctx) {
        String base = script.restatement();
        if (ctx.status == SceneContextStore.Status.AWAITING_CONFIRM) {
            return base + "。处理链已执行到人工确认节点，" + script.confirmPrompt();
        }
        if (ctx.status == SceneContextStore.Status.COMPLETED) {
            return base + "。已完成，结果分块如下。" + nextGuide(script);
        }
        return base;
    }

    private String nextGuide(SceneScript script) {
        return switch (script.sceneId()) {
            case "S1-1" -> "后续可回复：1 查看触达效果统计，2 将意向客户转入申请受理，3 结束本次营销。";
            case "S1-2" -> "后续可回复：1 查看活动实时进度，2 将意向客户转入商机跟进，3 结束活动并归档。";
            case "S1-3" -> "后续可回复：1 按高价值层生成触达方案，2 导出分层名单，3 结束。";
            case "S1-4" -> "后续可回复：1 查看激活效果，2 对已激活客户推荐产品，3 结束。";
            case "S1-5" -> "后续可回复：1 对点击客户安排回访，2 导出推荐名单，3 结束。";
            case "S1-6" -> "后续可回复：1 对已申请客户转入受理，2 导出本周线索转化报表，3 结束。";
            case "S1-7" -> "后续可回复：1 处理第一项待办，2 记录下一次拜访，3 结束。";
            default -> "本场景处理链已全部执行完毕，结果见下方分块与滚动日志。可直接输入新指令发起新场景。";
        };
    }

    private String reqId(String requestId) {
        return requestId == null || requestId.isBlank() ? "scene-" + System.nanoTime() : requestId;
    }

    /**
     * 统一入口标注（规范 1.3.5/1.4）：所有消息先经 S10-1 入口解析再分发。
     * 单诉求由入口直接分发至目标场景（普通场景不做二次意图解析）；S10-1 自身承接时为编排模式。
     */
    private String entryTag(SceneScript script, String via) {
        return script.sceneId().equals("S10-1")
                ? "【S10-1 统一入口 · 任务编排 · " + via + "】"
                : "【S10-1 入口分发 → " + script.sceneId() + " · " + script.title() + " · " + via + "】";
    }
}
