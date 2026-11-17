package com.bai.structuregen;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化生成服务（BIA-GEN-035）。基于非结构化文本/要点/数据/主题，按注册 Schema 生成
 * JSON/表格/卡片/大纲结构化输出，并执行字段级校验与实体归一映射。
 * <p>MVP 为确定性仿真：文本抽取用正则规则实现（LLM 语义抽取的规则降级兜底路径），
 * 输出 model=struct-gen-v2；生产可替换 LLM 抽取实现，接口契约不变。
 */
@Service
public class StructureGenService {

    private static final Logger log = LoggerFactory.getLogger(StructureGenService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_TEXT_LEN = 5000;      // 输入超长 STG_1006
    private static final List<String> INPUT_TYPES = List.of("TEXT", "POINTS", "DATA", "TOPIC");
    private static final List<String> OUTPUT_TYPES = List.of("JSON", "TABLE", "CARD", "OUTLINE");
    private static final List<String> OUTLINE_SECTIONS = List.of(
            "背景与目标", "现状分析", "方案要点", "风险与对策", "结论与建议");

    /** 抽取规则：确定性正则（struct_entity_rule 规则映射仿真） */
    private static final Pattern P_ORG = Pattern.compile(
            "[\\u4e00-\\u9fa5A-Za-z0-9（）()]{4,30}?(?:股份有限公司|有限公司|集团公司|集团|公司)");
    private static final Pattern P_REVENUE = Pattern.compile("营业收入\\s*([\\d,.]+)\\s*万");
    private static final Pattern P_PROFIT = Pattern.compile("净利润\\s*([\\d,.]+)\\s*万");
    private static final Pattern P_AMOUNT = Pattern.compile("(?:申请|申贷|授信)?\\s*(?:流动资金|经营|项目|消费|按揭)?贷款\\s*([\\d,.]+)\\s*万");
    private static final Pattern P_RATIO = Pattern.compile("(?:抵押率|质押率|评估比例|比率|成数)\\s*(\\d+(?:\\.\\d+)?)\\s*%");

    private final IdempotencyStore idempotency = new IdempotencyStore(7 * 24 * 3600_1000L);   // 幂等缓存 7 天

    /** Schema 注册表：schemaId → Schema（内置 + /schema 注册） */
    private final Map<String, Schema> schemas = new ConcurrentHashMap<>();
    /** 生成历史：append-only */
    private final List<StructureGenDtos.HistoryItem> history = new CopyOnWriteArrayList<>();
    private final AtomicLong seq = new AtomicLong();

    /** 内部 Schema 模型（含必填字段集） */
    private record Schema(String schemaId, String name, String version, String status,
                          List<String> fields, Set<String> required) {}

    public StructureGenService() {
        // 内置 Schema：尽调卡片（提示词库 A-36 业务示例）
        schemas.put("SCHEMA_DD_CARD_V2", new Schema("SCHEMA_DD_CARD_V2", "尽调摘要卡片", "2.0", "PUBLISHED",
                List.of("customerId", "customerName", "business", "annualRevenue", "netProfit",
                        "applyAmount", "applyPurpose", "guarantee", "loanRatio", "riskLevel"),
                Set.of("customerName", "applyAmount", "applyPurpose")));
        // 内置 Schema：报告大纲
        schemas.put("SCHEMA_REPORT_OUTLINE_V1", new Schema("SCHEMA_REPORT_OUTLINE_V1", "报告大纲", "1.0", "PUBLISHED",
                List.of("title", "sections"), Set.of("title")));
        // 内置 Schema：贷款要素表
        schemas.put("SCHEMA_LOAN_TABLE_V1", new Schema("SCHEMA_LOAN_TABLE_V1", "贷款要素表", "1.0", "PUBLISHED",
                List.of("customerName", "product", "amount", "term", "rate", "guarantee"), Set.of("customerName", "amount")));
    }

    /** 结构化生成（幂等键 requestId+bizKey+schemaId，同键 7 天复用） */
    public StructureGenDtos.GenerateResult generate(Map<String, Object> body, String requestId) {
        String schemaId = str(body.get("schemaId"));
        if (isBlank(schemaId)) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：schemaId 必填");
        }
        Schema schema = schemas.get(schemaId);
        if (schema == null || !"PUBLISHED".equals(schema.status())) {
            throw new BaiException(StructureGenErrorCodes.NOT_FOUND,
                    "STG_1004 Schema 不存在或未发布：" + schemaId);
        }
        String inputType = str(body.get("inputType"));
        if (isBlank(inputType)) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：inputType 必填");
        }
        inputType = inputType.toUpperCase();
        if (!INPUT_TYPES.contains(inputType)) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID,
                    "STG_1001 参数非法：inputType 仅支持 TEXT/POINTS/DATA/TOPIC");
        }
        String outputType = str(body.get("outputType"));
        if (isBlank(outputType)) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：outputType 必填");
        }
        outputType = outputType.toUpperCase();
        if (!OUTPUT_TYPES.contains(outputType)) {
            throw new BaiException(StructureGenErrorCodes.VALIDATION_FAILED,
                    "STG_1007 参数非法：outputType 仅支持 JSON/TABLE/CARD/OUTLINE");
        }
        Object input = body.get("input");
        if (input == null || (input instanceof String s && s.isBlank())) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：input 必填");
        }
        String inputText = input instanceof String s ? s : String.valueOf(input);
        if (inputText.length() > MAX_TEXT_LEN) {
            throw new BaiException(StructureGenErrorCodes.TOO_LONG,
                    "STG_1006 输入超长（" + inputText.length() + " > " + MAX_TEXT_LEN + " 字符），请缩减输入");
        }
        String bizKey = str(body.get("bizKey"));
        String idemKey = requestId + "|" + (bizKey == null ? "-" : bizKey) + "|" + schemaId;
        StructureGenDtos.GenerateResult cached = idempotency.get("generate", idemKey);
        if (cached != null) {
            return cached;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> facts = body.get("facts") instanceof Map<?, ?> f ? (Map<String, Object>) f : Map.of();

        long begin = System.nanoTime();
        // 1. 语义抽取/字段映射（规则映射仿真）
        Map<String, Object> extracted = extract(inputType, input, schema, facts);
        // 2. 生成组装：按 Schema 字段顺序组装
        Map<String, Object> result = assemble(outputType, schema, extracted);
        // 3. 字段校验：必填/非空
        List<String> issues = validate(schema, extracted);
        StructureGenDtos.ValidationResult validation = new StructureGenDtos.ValidationResult(issues.isEmpty(), issues);
        int entityMapped = extracted.size();
        String genId = "STG" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        StructureGenDtos.GenerateResult out = new StructureGenDtos.GenerateResult(
                genId, outputType, result, validation, entityMapped, "struct-gen-v2");
        history.add(new StructureGenDtos.HistoryItem(genId, requestId, schemaId, outputType,
                validation.passed(), entityMapped, LocalDateTime.now().withNano(0).toString()));
        idempotency.put("generate", idemKey, out);
        log.info("结构化生成完成: genId={}, schemaId={}, inputType={}, outputType={}, entityMapped={}, passed={}",
                genId, schemaId, inputType, outputType, entityMapped, validation.passed());
        log.debug("生成耗时仿真: {}ms", (System.nanoTime() - begin) / 1_000_000);
        return out;
    }

    /** Schema 注册（名/版本冲突 STG_1005） */
    public StructureGenDtos.SchemaInfo registerSchema(Map<String, Object> body, String requestId) {
        String schemaId = str(body.get("schemaId"));
        if (isBlank(schemaId)) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：schemaId 必填");
        }
        String name = str(body.get("name"));
        if (isBlank(name)) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数缺失：name 必填");
        }
        List<String> fields = body.get("fields") instanceof List<?> l
                ? l.stream().map(String::valueOf).toList() : List.of();
        if (fields.isEmpty()) {
            throw new BaiException(StructureGenErrorCodes.PARAM_INVALID, "STG_1001 参数非法：fields 字段清单必填");
        }
        String version = str(body.get("version"));
        if (isBlank(version)) {
            version = "1.0";
        }
        Set<String> required = body.get("required") instanceof List<?> r
                ? Set.copyOf(r.stream().map(String::valueOf).toList()) : Set.of();
        if (schemas.containsKey(schemaId)) {
            throw new BaiException(StructureGenErrorCodes.CONFLICT,
                    "STG_1005 Schema 名/版本冲突：" + schemaId + " 已存在，请更换 ID 或升级版本");
        }
        Schema schema = new Schema(schemaId, name, version, "PUBLISHED", List.copyOf(fields), required);
        schemas.put(schemaId, schema);
        log.info("Schema 注册: schemaId={}, name={}, version={}, fields={}, operator requestId={}",
                schemaId, name, version, fields.size(), requestId);
        return new StructureGenDtos.SchemaInfo(schemaId, name, version, "PUBLISHED", fields);
    }

    /** Schema 查询 */
    public List<StructureGenDtos.SchemaInfo> schemas() {
        return schemas.values().stream()
                .map(s -> new StructureGenDtos.SchemaInfo(s.schemaId(), s.name(), s.version(), s.status(), s.fields()))
                .toList();
    }

    /** 历史查询：requestId 过滤，未命中 STG_1004 */
    public List<StructureGenDtos.HistoryItem> history(String requestId, int limit) {
        List<StructureGenDtos.HistoryItem> matched = new ArrayList<>(history);
        if (!isBlank(requestId)) {
            matched.removeIf(h -> !requestId.equals(h.requestId()));
            if (matched.isEmpty()) {
                throw new BaiException(StructureGenErrorCodes.NOT_FOUND, "STG_1004 生成历史不存在：" + requestId);
            }
        }
        int n = Math.min(Math.max(limit, 1), 100);
        return matched.size() <= n ? matched : matched.subList(matched.size() - n, matched.size());
    }

    /** 按输入类型抽取字段（规则映射确定性仿真） */
    private Map<String, Object> extract(String inputType, Object input, Schema schema, Map<String, Object> facts) {
        Map<String, Object> extracted = new LinkedHashMap<>();
        switch (inputType) {
            case "TEXT" -> {
                String text = String.valueOf(input);
                for (String field : schema.fields()) {
                    Object v = extractField(field, text, facts);
                    if (v != null) {
                        extracted.put(field, v);
                    }
                }
            }
            case "POINTS" -> {
                List<?> points = input instanceof List<?> l ? l : List.of(input);
                int i = 0;
                for (String field : schema.fields()) {
                    if (i >= points.size()) {
                        break;
                    }
                    Object v = points.get(i++);
                    if (v != null && !String.valueOf(v).isBlank()) {
                        extracted.put(field, v);
                    }
                }
            }
            case "DATA" -> {
                if (input instanceof Map<?, ?> data) {
                    for (String field : schema.fields()) {
                        Object v = data.get(field);
                        if (v != null) {
                            extracted.put(field, v);
                        }
                    }
                }
            }
            case "TOPIC" -> {
                String topic = input instanceof Map<?, ?> m && m.get("topic") != null
                        ? String.valueOf(m.get("topic")) : String.valueOf(input);
                extracted.put("title", topic);
                extracted.put("sections", OUTLINE_SECTIONS);
            }
            default -> { /* 不可达：inputType 已校验 */ }
        }
        return extracted;
    }

    /** 单字段抽取规则（SCHEMA_DD_CARD_V2 为例，其余字段通用回退） */
    private Object extractField(String field, String text, Map<String, Object> facts) {
        return switch (field) {
            case "customerId" -> facts.containsKey("customerId") ? facts.get("customerId")
                    : "C" + String.format("%09d", Math.abs(text.hashCode()) % 1_000_000_000);
            case "customerName" -> first(P_ORG.matcher(text));
            case "business" -> containsAny(text, "机械") ? "机械设备制造"
                    : containsAny(text, "商贸") ? "商贸流通" : containsAny(text, "科技") ? "科技服务" : null;
            case "annualRevenue" -> numOf(P_REVENUE.matcher(text));
            case "netProfit" -> numOf(P_PROFIT.matcher(text));
            case "applyAmount" -> numOf(P_AMOUNT.matcher(text));
            case "applyPurpose" -> text.contains("流动资金贷款") ? "流动资金贷款"
                    : text.contains("经营贷") ? "经营贷款"
                    : text.contains("项目贷款") ? "项目贷款"
                    : text.contains("消费贷") ? "消费贷款" : null;
            case "guarantee" -> text.contains("厂房抵押") ? "厂房抵押"
                    : text.contains("土地抵押") ? "土地抵押"
                    : text.contains("存单质押") ? "存单质押"
                    : text.contains("应收账款") ? "应收账款质押"
                    : text.contains("设备抵押") ? "设备抵押"
                    : text.contains("保证") ? "保证担保" : null;
            case "loanRatio" -> numOf(P_RATIO.matcher(text));
            case "riskLevel" -> {
                Double ratio = numOf(P_RATIO.matcher(text));
                yield ratio == null ? null
                        : ratio >= 75 ? "HIGH" : ratio >= 70 ? "MEDIUM" : "LOW";
            }
            case "product" -> text.contains("经营贷") ? "经营贷款" : text.contains("消费贷") ? "消费贷款" : null;
            case "term" -> {
                Matcher m = Pattern.compile("(\\d+)\\s*(?:个月|月)").matcher(text);
                yield m.find() ? Integer.parseInt(m.group(1)) : null;
            }
            case "rate" -> {
                Matcher m = Pattern.compile("年化\\s*(\\d+(?:\\.\\d+)?)\\s*%").matcher(text);
                yield m.find() ? Double.parseDouble(m.group(1)) : null;
            }
            default -> null;    // 未知字段无规则映射，不臆造
        };
    }

    /** 按 outputType 组装结果 */
    private Map<String, Object> assemble(String outputType, Schema schema, Map<String, Object> extracted) {
        Map<String, Object> result = new LinkedHashMap<>();
        switch (outputType) {
            case "JSON" -> result.putAll(extracted);
            case "CARD" -> {
                result.put("title", schema.name());
                result.putAll(extracted);
            }
            case "TABLE" -> {
                result.put("columns", schema.fields());
                result.put("rows", List.of(schema.fields().stream()
                        .map(f -> extracted.getOrDefault(f, "")).toList()));
            }
            case "OUTLINE" -> {
                result.put("title", extracted.getOrDefault("title", schema.name()));
                result.put("sections", extracted.getOrDefault("sections", OUTLINE_SECTIONS));
            }
            default -> { /* 不可达：outputType 已校验 */ }
        }
        return result;
    }

    /** 必填字段校验（枚举零越界：已知枚举字段不在候选集时计入 issues） */
    private List<String> validate(Schema schema, Map<String, Object> extracted) {
        List<String> issues = new ArrayList<>();
        for (String required : schema.required()) {
            if (!extracted.containsKey(required) || extracted.get(required) == null
                    || String.valueOf(extracted.get(required)).isBlank()) {
                issues.add("必填字段缺失: " + required);
            }
        }
        Object risk = extracted.get("riskLevel");
        if (risk != null && !Arrays.asList("LOW", "MEDIUM", "HIGH").contains(String.valueOf(risk))) {
            issues.add("枚举越界: riskLevel=" + risk);
        }
        return issues;
    }

    private static String first(Matcher m) {
        return m.find() ? m.group().trim() : null;
    }

    private static Double numOf(Matcher m) {
        return m.find() ? Double.parseDouble(m.group(1).replace(",", "")) : null;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
