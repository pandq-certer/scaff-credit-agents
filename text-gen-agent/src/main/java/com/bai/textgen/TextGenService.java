package com.bai.textgen;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本生成服务（BIA-GEN-034）。全行统一文本创作原子：模板生成（确定性）、自由生成、混合模式。
 * <p>处理链：校验入参（mode/style 白名单、maxLength 10~5000、points≤20）→ 模式路由（模板引擎确定性渲染，
 * MVP 不调 LLM）→ 事实注入（{{var}} 占位符，facts 不得改写）→ 合规检查（RED 红线命中即 REJECT 422，
 * 不得返回文本）→ 长度控制（maxLength ±10% 截断/补齐）→ 留痕。
 */
@Service
public class TextGenService {

    private static final Logger log = LoggerFactory.getLogger(TextGenService.class);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z0-9_.]+)}}");
    private static final String MODEL = "template-engine";
    private static final Set<String> MODES = Set.of("TEMPLATE", "FREE", "MIXED");
    private static final Set<String> STYLES = Set.of("FORMAL", "FRIENDLY", "WARN", "MARKET", "CUSTOM");
    private static final int MAX_POINTS = 20;
    private static final int MAX_LENGTH = 5000;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /** RED 营销红线词表（零容忍，命中即 REJECT 422） */
    private static final List<String> RED_WORDS = List.of("稳赚", "稳赚不赔", "保本", "保本保收益", "无风险",
            "零风险", "必赚", "只赚不赔", "绝对收益", "百分百中奖");

    /** 自由生成风格框架（5 种风格各一句框架文案） */
    private static final Map<String, String> STYLE_FRAMES = Map.of(
            "FORMAL", "根据以下要点：%s，特此说明。",
            "FRIENDLY", "您好！跟您同步几项要点：%s。如有任何疑问，欢迎随时联系我们，很高兴为您服务。",
            "WARN", "特别提醒：请您关注以下事项：%s。请务必及时处理，逾期可能影响您的征信记录。",
            "MARKET", "专属好礼不容错过！为您呈上本期要点：%s。名额有限，立即行动，畅享专属优惠！",
            "CUSTOM", "根据您的要求，整理要点如下：%s。以上内容供您参考。");

    /** 长度补齐句子池（按风格循环追加，超长则截断） */
    private static final Map<String, List<String>> CLOSINGS = Map.of(
            "FORMAL", List.of("以上内容经审核确认，如有未尽事宜以正式发文为准。", "特此说明。", "本说明自印发之日起施行。"),
            "FRIENDLY", List.of("感谢您的理解与支持，祝您生活愉快！", "如需进一步了解，欢迎随时联系我们。", "期待您的光临！"),
            "WARN", List.of("请您高度重视，及时处理，避免造成不必要的损失。", "如有疑问，请联系您的客户经理核实确认。",
                    "逾期未处理将可能影响您的信用记录。"),
            "MARKET", List.of("名额有限，先到先得，立即行动！", "更多专属权益等您来体验，马上咨询吧！", "活动详情以网点公告为准。"),
            "CUSTOM", List.of("以上为按您要求生成的参考内容。", "如需调整风格或要点，请重新提交生成请求。",
                    "本内容可根据实际业务需要进一步修改。"));

    private final IdempotencyStore idempotency = new IdempotencyStore(7 * 24 * 3600 * 1000L); // 幂等结果缓存 7 天

    /** 模板注册表（内存 Map，生产落库并按业务域授权） */
    private final Map<String, TextGenDtos.TemplateInfo> templates = new ConcurrentHashMap<>();
    /** 生成历史（append-only，生产落库保留≥3 年） */
    private final List<TextGenDtos.GenRecord> history = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    public TextGenService() {
        // 预置注册模板（示例：催收提醒、放款通知）
        templates.put("TMPL_COLLECT_001", new TextGenDtos.TemplateInfo("TMPL_COLLECT_001", "催收逾期提醒", "COLLECTION",
                "尊敬的{{customerName}}：您的贷款 {{loanNo}} 已逾期 {{days}} 天，请尽快处理，逾期将影响您的征信记录。"
                        + "如有疑问请联系您的客户经理。", LocalDateTime.now().toString()));
        templates.put("TMPL_NOTICE_001", new TextGenDtos.TemplateInfo("TMPL_NOTICE_001", "放款成功通知", "NOTICE",
                "{{customerName}}，您申请的贷款 {{loanNo}} 已于 {{date}} 放款成功，金额 {{amount}} 元，请留意入账信息。",
                LocalDateTime.now().toString()));
    }

    /** 文本生成（同步，幂等键 requestId+bizKey+mode，同键复用不重复生成） */
    public TextGenDtos.GenerateResult generate(String mode, String templateId, List<?> points,
                                               Map<String, Object> facts, String style, Integer maxLength,
                                               String bizKey, String requestId) {
        // 1. 入参校验
        if (mode == null || mode.isBlank()) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数缺失：mode 必填（TEMPLATE/FREE/MIXED）");
        }
        String m = mode.trim().toUpperCase();
        if (!MODES.contains(m)) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数非法：mode 须为 TEMPLATE/FREE/MIXED");
        }
        if (style == null || style.isBlank()) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数缺失：style 必填");
        }
        String st = style.trim().toUpperCase();
        if (!STYLES.contains(st)) {
            throw new BaiException(TextGenErrorCodes.COMPLIANCE_REJECT,
                    "TXG_1007 风格非法：style 须为 FORMAL/FRIENDLY/WARN/MARKET/CUSTOM");
        }
        int target = maxLength == null ? 200 : maxLength;
        if (target < 10 || target > MAX_LENGTH) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数非法：maxLength 须在 10~5000，默认 200");
        }
        if (points != null && points.size() > MAX_POINTS) {
            throw new BaiException(TextGenErrorCodes.INPUT_TOO_LONG,
                    "TXG_1006 要点超长：" + points.size() + " 条超限（≤20 条），请缩减输入");
        }
        if ("FREE".equals(m) && (points == null || points.isEmpty())) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数缺失：FREE 模式 points 必填");
        }
        TextGenDtos.TemplateInfo tpl = null;
        if ("TEMPLATE".equals(m) || "MIXED".equals(m)) {
            if (templateId == null || templateId.isBlank()) {
                throw new BaiException(TextGenErrorCodes.COMPLIANCE_REJECT,
                        "TXG_1007 参数缺失：TEMPLATE/MIXED 模式 templateId 必填");
            }
            tpl = templates.get(templateId);
            if (tpl == null) {
                throw new BaiException(TextGenErrorCodes.TEMPLATE_NOT_FOUND, "TXG_1004 模板不存在或未注册：" + templateId);
            }
        }
        // 2. 幂等（requestId+bizKey+mode）
        String key = (bizKey == null || bizKey.isBlank() ? (templateId == null ? "FREE" : templateId) : bizKey) + ":" + m;
        TextGenDtos.GenerateResult cached = idempotency.get(key, requestId);
        if (cached != null) {
            return cached;
        }
        // 3. 确定性生成（模板引擎，不调 LLM）
        List<String> pts = points == null ? List.of()
                : points.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
        String text;
        if ("TEMPLATE".equals(m)) {
            text = render(tpl.content(), facts);
        } else if ("FREE".equals(m)) {
            text = assembleFree(pts, st);
        } else {
            text = render(tpl.content(), facts);
            if (!pts.isEmpty()) {
                text = text + assembleFree(pts, st);
            }
        }
        // 4. 合规检查：RED 红线零容忍，命中不得返回文本
        String genId = "TXG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        List<String> riskWords = RED_WORDS.stream().filter(text::contains).toList();
        if (!riskWords.isEmpty()) {
            TextGenDtos.GenerateResult blocked = new TextGenDtos.GenerateResult("", genId, m,
                    new TextGenDtos.Compliance(false, riskWords), 0, MODEL);
            history.add(new TextGenDtos.GenRecord(genId, requestId, m, st, templateId, "", 0, false,
                    LocalDateTime.now().toString()));
            idempotency.put(key, requestId, blocked);
            log.warn("红线拦截: requestId={}, genId={}, riskWords={}", requestId, genId, riskWords);
            return blocked;
        }
        // 5. 长度控制 ±10%
        text = fitLength(text, target, st);
        TextGenDtos.GenerateResult result = new TextGenDtos.GenerateResult(text, genId, m,
                new TextGenDtos.Compliance(true, List.of()), text.length(), MODEL);
        history.add(new TextGenDtos.GenRecord(genId, requestId, m, st, templateId, text, text.length(), true,
                LocalDateTime.now().toString()));
        idempotency.put(key, requestId, result);
        log.info("文本生成: requestId={}, genId={}, mode={}, style={}, length={}", requestId, genId, m, st, text.length());
        return result;
    }

    /** 模板管理：注册/更新（{{var}} 占位符，内存 Map 存储） */
    public TextGenDtos.TemplateInfo registerTemplate(String templateId, String name, String scene, String content) {
        if (templateId == null || templateId.isBlank()) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数缺失：templateId 必填");
        }
        if (content == null || content.isBlank()) {
            throw new BaiException(TextGenErrorCodes.PARAM_INVALID, "TXG_1001 参数缺失：content 必填");
        }
        TextGenDtos.TemplateInfo info = new TextGenDtos.TemplateInfo(templateId,
                name == null || name.isBlank() ? templateId : name,
                scene == null || scene.isBlank() ? "GENERAL" : scene, content, LocalDateTime.now().toString());
        templates.put(templateId, info);
        log.info("模板注册: templateId={}, scene={}", templateId, info.scene());
        return info;
    }

    /** 模板查询 */
    public TextGenDtos.TemplateList listTemplates() {
        return new TextGenDtos.TemplateList(templates.size(), List.copyOf(templates.values()));
    }

    /** 生成历史查询（按 genId/requestId 过滤，最新优先） */
    public TextGenDtos.HistoryResult history(String requestId, String genId) {
        List<TextGenDtos.GenRecord> items = new ArrayList<>(history.stream()
                .filter(r -> genId == null || genId.isBlank() || genId.equals(r.genId()))
                .filter(r -> requestId == null || requestId.isBlank() || requestId.equals(r.requestId()))
                .toList());
        Collections.reverse(items);
        return new TextGenDtos.HistoryResult(items.size(), items);
    }

    /** 模板渲染：{{var}} 占位符替换，facts 注入且不得改写，变量缺失拒绝（TXG_1007） */
    private String render(String content, Map<String, Object> facts) {
        Map<String, Object> safeFacts = facts == null ? Map.of() : facts;
        Matcher matcher = PLACEHOLDER.matcher(content);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = safeFacts.get(key);
            if (value == null || String.valueOf(value).isBlank()) {
                throw new BaiException(TextGenErrorCodes.COMPLIANCE_REJECT,
                        "TXG_1007 模板变量缺失：{{" + key + "}}，请在 facts 补全后重试");
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(value)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 自由生成（确定性仿真）：要点按风格框架组装成一段连贯中文文本 */
    private String assembleFree(List<String> points, String style) {
        StringBuilder numbered = new StringBuilder();
        for (int i = 0; i < points.size(); i++) {
            if (i > 0) {
                numbered.append("；");
            }
            numbered.append(i + 1).append(")").append(points.get(i));
        }
        return String.format(STYLE_FRAMES.getOrDefault(style, STYLE_FRAMES.get("CUSTOM")), numbered);
    }

    /** 长度控制：超长截断优先保留关键信息，不足按风格句子池补齐（±10%） */
    private String fitLength(String text, int target, String style) {
        int lower = (int) Math.floor(target * 0.9);
        int upper = (int) Math.ceil(target * 1.1);
        if (text.length() > upper) {
            return text.substring(0, target);
        }
        if (text.length() >= lower) {
            return text;
        }
        List<String> pool = CLOSINGS.getOrDefault(style, CLOSINGS.get("CUSTOM"));
        StringBuilder sb = new StringBuilder(text);
        int i = 0;
        while (sb.length() < lower && i < pool.size() * 10) {
            sb.append(pool.get(i % pool.size()));
            i++;
        }
        String padded = sb.toString();
        return padded.length() > upper ? padded.substring(0, target) : padded;
    }
}
