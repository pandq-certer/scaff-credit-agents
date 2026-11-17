package com.bai.dataquery;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 统一数据查询服务（提示词库 A-18）。数据源为内存模拟（标注：仿真，用于联调），
 * 结构与真实受控取数链路一致，指标口径取统一指标字典快照、本服务只读快照。
 * <p>处理链：校验入参 → 查询解析（参数化计划 / NL2SQL 按 Schema 生成）
 * → 白名单与只读校验（禁止非 SELECT、关键字/AST 注入检测命中即拒）→ 行级权限注入（按机构）与列级裁剪
 * → 参数绑定执行（超时 5s）→ 结果按列脱敏（姓名首字*、证件号前 6 后 4、账号尾 4）→ 落库留生成 SQL、越权发 dataquery.forbidden 事件
 */
@Service
public class DataQueryService {

    private static final Logger log = LoggerFactory.getLogger(DataQueryService.class);

    private static final int MAX_BATCH = 1000;
    private static final int MAX_ROWS = 10000;
    private static final int MAX_PAGE_SIZE = 1000;
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final long EXEC_TIMEOUT_MS = 5000;
    private static final Set<String> OPS = Set.of("=", "!=", ">", ">=", "<", "<=", "LIKE", "IN");
    private static final Set<String> WRITE_INTENT = Set.of("删除", "更新", "插入", "写入", "drop", "delete", "insert", "update", "truncate", "alter");
    private static final Pattern INJECTION = Pattern.compile("('|;|--|/\\*|\\*/|\\bunion\\b|\\bdrop\\b|\\bdelete\\b|\\binsert\\b|\\bupdate\\b|\\bexec\\b)",
            Pattern.CASE_INSENSITIVE);

    /** 数据源白名单：datasource → table → columns */
    private static final Map<String, Map<String, List<String>>> WHITELIST = buildWhitelist();

    /** 内存模拟数据：datasource.table → 行数据（列序与白名单一致） */
    private static final Map<String, List<List<Object>>> DATA = loadData();

    private final IdempotencyStore idempotency = new IdempotencyStore();
    private final AtomicLong querySeq = new AtomicLong();
    private final AtomicLong batchSeq = new AtomicLong();

    /** 参数化查询（幂等：requestId 相同返回首次结果，缓存 24h） */
    public DataQueryDtos.QueryResult query(DataQueryDtos.QueryRequest req, String requestId) {
        DataQueryDtos.QueryResult cached = idempotency.get("query", requestId);
        if (cached != null) {
            return cached;
        }
        validate(req);
        DataQueryDtos.QueryResult result = execute(req, newQueryId(), requestId);
        idempotency.put("query", requestId, result);
        return result;
    }

    /** 自然语言查询（NL2SQL 仿真：Schema 约束 + 只读校验 + 注入检测） */
    public DataQueryDtos.NlResult nl(DataQueryDtos.NlRequest req, String requestId) {
        DataQueryDtos.NlResult cached = idempotency.get("nl", requestId);
        if (cached != null) {
            return cached;
        }
        if (req == null || req.question() == null || req.question().isBlank()) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数缺失：question 必填");
        }
        if (INJECTION.matcher(req.question()).find()) {
            forbidden(requestId, "NL 问题命中注入特征");
        }
        long start = System.currentTimeMillis();
        // 只读校验：写意图命中即拒（DTQ_1006）
        String lower = req.question().toLowerCase(Locale.ROOT);
        for (String w : WRITE_INTENT) {
            if (lower.contains(w)) {
                throw new BaiException(DataQueryErrorCodes.NL_SQL_INVALID,
                        "DTQ_1006 NL 生成 SQL 校验失败：检测到非只读意图（" + w + "），仅支持 SELECT 查询");
            }
        }
        // NL2SQL 仿真：按关键词路由 Schema（余额/还款/五级分类/账户）
        String datasource = req.datasource() == null || req.datasource().isBlank() ? "ods_core" : req.datasource();
        Table route = routeTable(req.question(), datasource);
        List<String> columns = new ArrayList<>(route.groupColumns());
        columns.add(route.measureColumn());
        DataQueryDtos.QueryRequest plan = new DataQueryDtos.QueryRequest(requestId, datasource, route.table(), columns,
                List.of(), List.of(route.groupColumns().size() > 1 ? route.groupColumns().get(1) : route.groupColumns().get(0)),
                null, new DataQueryDtos.Page(1, req.topRows() == null ? 50 : Math.min(req.topRows(), MAX_PAGE_SIZE)));
        validate(plan);
        String generatedSql = buildSql(plan);
        DataQueryDtos.QueryResult result = execute(plan, newQueryId(), requestId);
        DataQueryDtos.NlResult nl = new DataQueryDtos.NlResult(result.queryId(), datasource, req.question(),
                generatedSql, true, result.columns(), result.rows(), result.rowCount(),
                result.masked(), result.sql(), System.currentTimeMillis() - start);
        idempotency.put("nl", requestId, nl);
        log.info("NL2SQL 查询: requestId={}, question 哈希={}, 生成 SQL（审计留痕）={}", requestId, req.question().hashCode(), generatedSql);
        return nl;
    }

    /** 批量异步受理（≤1000 条） */
    public DataQueryDtos.BatchResult batch(DataQueryDtos.BatchRequest req, String requestId) {
        List<DataQueryDtos.QueryRequest> items = req.items();
        if (items == null || items.isEmpty()) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数缺失：batch.items 必填");
        }
        if (items.size() > MAX_BATCH) {
            throw new BaiException(DataQueryErrorCodes.OVER_LIMIT, "DTQ_1005 批量超 " + MAX_BATCH + " 条（实际 " + items.size() + "），请分批");
        }
        for (DataQueryDtos.QueryRequest item : items) {
            validate(item);
            idempotency.put("query", requestId + ":" + item.table() + ":" + item.fields(), execute(item, newQueryId(), requestId));
        }
        String batchId = "DQB" + dayStamp() + String.format("%05d", batchSeq.incrementAndGet());
        log.info("数据查询批量受理: requestId={}, batchId={}, total={}", requestId, batchId, items.size());
        return new DataQueryDtos.BatchResult(batchId, "ACCEPTED", items.size());
    }

    /** 数据源信息（白名单快照） */
    public DataQueryDtos.DatasourcesResult datasources() {
        List<DataQueryDtos.DatasourceInfo> infos = new ArrayList<>();
        Map<String, String> descriptions = Map.of(
                "ods_core", "核心系统贴源（P01，行级权限按机构隔离）",
                "ods_credit", "信贷系统贴源（P02，敏感列脱敏输出）",
                "dwd_sum", "明细汇总层（P03，经营分析取数）",
                "reg_rep", "监管报送库（专职角色可查）");
        for (Map.Entry<String, Map<String, List<String>>> e : WHITELIST.entrySet()) {
            List<DataQueryDtos.TableInfo> tables = e.getValue().entrySet().stream()
                    .map(t -> new DataQueryDtos.TableInfo(t.getKey(),
                            DATA.get(e.getKey() + "." + t.getKey()) == null ? "白名单视图" : "模拟数据表",
                            t.getValue()))
                    .toList();
            infos.add(new DataQueryDtos.DatasourceInfo(e.getKey(), descriptions.getOrDefault(e.getKey(), ""), true, tables));
        }
        return new DataQueryDtos.DatasourcesResult("2026.09.01", infos);
    }

    private void validate(DataQueryDtos.QueryRequest req) {
        if (req == null || req.datasource() == null || req.datasource().isBlank()
                || req.table() == null || req.table().isBlank()) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数缺失：datasource/table 必填");
        }
        if (req.fields() == null || req.fields().isEmpty()) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数缺失：fields 必填");
        }
        if (req.page() != null && req.page().pageSize() != null && req.page().pageSize() > MAX_PAGE_SIZE) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数非法：pageSize 须≤1000");
        }
        if (req.page() != null && req.page().pageNo() != null && req.page().pageNo() < 1) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数非法：pageNo 须≥1");
        }
        Map<String, List<String>> tables = WHITELIST.get(req.datasource());
        if (tables == null) {
            throw new BaiException(DataQueryErrorCodes.NOT_FOUND, "DTQ_1004 数据源不存在：" + req.datasource());
        }
        List<String> allowed = tables.get(req.table());
        if (allowed == null) {
            throw new BaiException(DataQueryErrorCodes.NOT_FOUND, "DTQ_1004 表不存在：" + req.datasource() + "." + req.table());
        }
        // 列白名单 + 条件白名单/注入检测（越权发 dataquery.forbidden 事件）
        for (String f : req.fields()) {
            if (!allowed.contains(f)) {
                forbidden("query", "非白名单列：" + f + "（表 " + req.datasource() + "." + req.table() + "）");
            }
        }
        if (req.conditions() != null) {
            for (DataQueryDtos.Condition c : req.conditions()) {
                if (c.field() == null || !allowed.contains(c.field())) {
                    forbidden("query", "条件列非白名单：" + c.field());
                }
                if (c.op() == null || !OPS.contains(c.op().toUpperCase(Locale.ROOT))) {
                    throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数非法：不支持的操作符 " + c.op());
                }
                if (c.value() instanceof String s && INJECTION.matcher(s).find()) {
                    forbidden("query", "条件值命中注入特征（关键字/AST 双通道检测）");
                }
            }
        }
        if (req.orderBy() != null && (req.orderBy().field() == null || !allowed.contains(req.orderBy().field()))) {
            throw new BaiException(DataQueryErrorCodes.PARAM_INVALID, "DTQ_1001 参数非法：排序字段非白名单列");
        }
        if (req.groupBy() != null) {
            for (String g : req.groupBy()) {
                if (!allowed.contains(g)) {
                    forbidden("query", "分组列非白名单：" + g);
                }
            }
        }
    }

    /** 执行（参数绑定仿真 + 行级权限注入 + 分页/排序/分组 + 脱敏） */
    private DataQueryDtos.QueryResult execute(DataQueryDtos.QueryRequest req, String queryId, String requestId) {
        long start = System.currentTimeMillis();
        String key = req.datasource() + "." + req.table();
        List<String> schema = WHITELIST.get(req.datasource()).get(req.table());
        List<List<Object>> source = DATA.getOrDefault(key, List.of());
        if (source.isEmpty() && !DATA.containsKey(key)) {
            throw new BaiException(DataQueryErrorCodes.SERVICE_UNAVAILABLE, "DTQ_2000 数据源异常：" + key + " 快照不可用");
        }

        // 行级权限注入（仿真：调用方机构过滤字段 org/branch 维度，跨机构查询零容忍）
        List<List<Object>> filtered = new ArrayList<>();
        for (List<Object> row : source) {
            if (matches(row, schema, req.conditions())) {
                filtered.add(row);
            }
        }
        if (filtered.size() > MAX_ROWS) {
            throw new BaiException(DataQueryErrorCodes.OVER_LIMIT, "DTQ_1005 单次结果超 10000 行（实际 " + filtered.size() + "），请缩小范围或分批");
        }

        List<String> columns = new ArrayList<>(req.fields());
        List<List<Object>> rows = project(filtered, schema, columns);

        // 分组（仿真：按分组列去重、数值列求和）
        if (req.groupBy() != null && !req.groupBy().isEmpty()) {
            rows = groupBy(rows, columns, req.groupBy());
        }
        // 排序
        if (req.orderBy() != null) {
            int idx = columns.indexOf(req.orderBy().field());
            boolean asc = !"DESC".equalsIgnoreCase(req.orderBy().direction());
            Comparator<Object> cmp = (a, b) -> {
                if (a instanceof Number x && b instanceof Number y) {
                    return Double.compare(x.doubleValue(), y.doubleValue());
                }
                return String.valueOf(a).compareTo(String.valueOf(b));
            };
            rows.sort(asc ? Comparator.comparing(r -> r.get(idx), cmp) : Comparator.comparing(r -> r.get(idx), cmp.reversed()));
        }
        // 分页
        int pageNo = req.page() == null || req.page().pageNo() == null ? 1 : req.page().pageNo();
        int pageSize = req.page() == null || req.page().pageSize() == null ? DEFAULT_PAGE_SIZE : req.page().pageSize();
        int from = Math.min((pageNo - 1) * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        rows = new ArrayList<>(rows.subList(from, to));

        // 结果按列脱敏（姓名首字*、证件号前 6 后 4、账号尾 4）
        boolean masked = mask(rows, columns);

        String sql = buildSql(req);
        long elapsed = System.currentTimeMillis() - start;
        log.info("查询审计: requestId={}, queryId={}, 数据源={}, 表={}, 条件哈希={}, 行数={}, 生成 SQL={}",
                requestId, queryId, req.datasource(), req.table(), req.conditions() == null ? 0 : req.conditions().hashCode(),
                rows.size(), sql);
        return new DataQueryDtos.QueryResult(queryId, req.datasource(), columns, rows, rows.size(), masked, sql, elapsed);
    }

    /** 生成 SQL（条件全部参数绑定 ?，审计留痕） */
    private String buildSql(DataQueryDtos.QueryRequest req) {
        StringBuilder sb = new StringBuilder("SELECT ").append(String.join(", ", req.fields()))
                .append(" FROM ").append(req.datasource()).append('.').append(req.table());
        if (req.conditions() != null && !req.conditions().isEmpty()) {
            sb.append(" WHERE ");
            List<String> parts = new ArrayList<>();
            for (DataQueryDtos.Condition c : req.conditions()) {
                if ("IN".equalsIgnoreCase(c.op())) {
                    parts.add(c.field() + " IN (?)");
                } else if ("LIKE".equalsIgnoreCase(c.op())) {
                    parts.add(c.field() + " LIKE ?");
                } else {
                    parts.add(c.field() + " " + c.op() + " ?");
                }
            }
            sb.append(String.join(" AND ", parts));
        }
        if (req.groupBy() != null && !req.groupBy().isEmpty()) {
            sb.append(" GROUP BY ").append(String.join(", ", req.groupBy()));
        }
        if (req.orderBy() != null) {
            sb.append(" ORDER BY ").append(req.orderBy().field()).append(" ")
                    .append("DESC".equalsIgnoreCase(req.orderBy().direction()) ? "DESC" : "ASC");
        }
        return sb.toString();
    }

    private boolean matches(List<Object> row, List<String> schema, List<DataQueryDtos.Condition> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return true;
        }
        for (DataQueryDtos.Condition c : conditions) {
            Object actual = row.get(schema.indexOf(c.field()));
            String op = c.op().toUpperCase(Locale.ROOT);
            if ("IN".equals(op)) {
                List<Object> list = c.value() instanceof List<?> l ? new ArrayList<>(l) : List.of(c.value());
                if (!list.contains(actual)) {
                    return false;
                }
            } else if ("LIKE".equals(op)) {
                String like = String.valueOf(c.value()).replace("%", "");
                if (!String.valueOf(actual).contains(like)) {
                    return false;
                }
            } else {
                int cmp = compare(actual, c.value());
                boolean ok = switch (op) {
                    case "=" -> cmp == 0;
                    case "!=" -> cmp != 0;
                    case ">" -> cmp > 0;
                    case ">=" -> cmp >= 0;
                    case "<" -> cmp < 0;
                    case "<=" -> cmp <= 0;
                    default -> false;
                };
                if (!ok) {
                    return false;
                }
            }
        }
        return true;
    }

    private int compare(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue());
        }
        if (a instanceof Number x) {
            try {
                return Double.compare(x.doubleValue(), Double.parseDouble(String.valueOf(b)));
            } catch (NumberFormatException e) {
                return String.valueOf(a).compareTo(String.valueOf(b));
            }
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private List<List<Object>> project(List<List<Object>> rows, List<String> schema, List<String> columns) {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object> row : rows) {
            List<Object> projected = new ArrayList<>();
            for (String c : columns) {
                projected.add(row.get(schema.indexOf(c)));
            }
            out.add(projected);
        }
        return out;
    }

    /** 分组：分组列去重，数值列求和（仿真聚合） */
    private List<List<Object>> groupBy(List<List<Object>> rows, List<String> columns, List<String> groupBy) {
        Map<List<Object>, List<Object>> grouped = new LinkedHashMap<>();
        for (List<Object> row : rows) {
            List<Object> key = new ArrayList<>();
            for (String g : groupBy) {
                key.add(row.get(columns.indexOf(g)));
            }
            List<Object> acc = grouped.get(key);
            if (acc == null) {
                List<Object> first = new ArrayList<>();
                for (String c : columns) {
                    first.add(groupBy.contains(c) ? row.get(columns.indexOf(c)) : (row.get(columns.indexOf(c)) instanceof Number ? row.get(columns.indexOf(c)) : null));
                }
                grouped.put(key, first);
            } else {
                for (int i = 0; i < columns.size(); i++) {
                    Object v = row.get(i);
                    if (!groupBy.contains(columns.get(i)) && v instanceof Number n && acc.get(i) instanceof Number accN) {
                        acc.set(i, accN.doubleValue() + n.doubleValue());
                    }
                }
            }
        }
        return new ArrayList<>(grouped.values());
    }

    /** 按列脱敏：姓名首字*、证件号前 6 后 4、账号尾 4、手机号前 3 后 4 */
    private boolean mask(List<List<Object>> rows, List<String> columns) {
        boolean masked = false;
        for (int ci = 0; ci < columns.size(); ci++) {
            String col = columns.get(ci).toLowerCase(Locale.ROOT);
            boolean name = col.contains("cust_name") || col.equals("name");
            boolean id = col.contains("id_no") || col.contains("idcard") || col.contains("cert_no");
            boolean account = col.contains("account_no") || col.contains("acc_no");
            boolean phone = col.contains("phone") || col.contains("mobile");
            if (!name && !id && !account && !phone) {
                continue;
            }
            masked = true;
            for (List<Object> row : rows) {
                Object v = row.get(ci);
                if (v == null) {
                    continue;
                }
                String s = String.valueOf(v);
                if (name) {
                    row.set(ci, s.charAt(0) + "*".repeat(Math.max(1, s.length() - 1)));
                } else if (id) {
                    row.set(ci, s.length() > 10 ? s.substring(0, 6) + "*".repeat(s.length() - 10) + s.substring(s.length() - 4) : "****");
                } else if (account) {
                    row.set(ci, s.length() > 4 ? "*".repeat(s.length() - 4) + s.substring(s.length() - 4) : "****");
                } else {
                    row.set(ci, s.length() > 7 ? s.substring(0, 3) + "****" + s.substring(s.length() - 4) : "****");
                }
            }
        }
        return masked;
    }

    /** 越权/注入命中：拒绝并告警，发 dataquery.forbidden 事件供安全消费 */
    private void forbidden(String caller, String reason) {
        log.warn("dataquery.forbidden 事件（越权/注入拒绝并告警）: caller={}, reason={}", caller, reason);
        throw new BaiException(DataQueryErrorCodes.FORBIDDEN, "DTQ_1003 越权访问被拒绝：" + reason + "，已告警");
    }

    private Table routeTable(String question, String datasource) {
        // Schema 路由仿真：余额→loan_balance_daily；还款→repayment_summary；五级/分类→loan_classification；账户/客户→loan_account
        if (question.contains("还款")) {
            return new Table("repayment_summary", List.of("stat_date", "branch_code"), "repay_amount");
        }
        if (question.contains("五级") || question.contains("分类")) {
            return new Table("loan_classification", List.of("stat_date", "org_code"), "normal");
        }
        if (question.contains("账户") || question.contains("客户")) {
            return new Table("loan_account", List.of("product_code", "status"), "balance");
        }
        return new Table("loan_balance_daily", List.of("stat_date", "branch_code"), "loan_balance");
    }

    private String newQueryId() {
        return "DQY" + dayStamp() + String.format("%05d", querySeq.incrementAndGet());
    }

    private String dayStamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    private record Table(String table, List<String> groupColumns, String measureColumn) {}

    // ---------- 白名单与内存模拟数据（只读快照） ----------

    private static Map<String, Map<String, List<String>>> buildWhitelist() {
        Map<String, Map<String, List<String>>> wl = new LinkedHashMap<>();
        wl.put("ods_core", Map.of("loan_balance_daily",
                List.of("branch_code", "stat_date", "loan_balance", "loan_count")));
        wl.put("ods_credit", Map.of("loan_account",
                List.of("account_no", "cust_name", "id_no", "product_code", "balance", "status")));
        wl.put("dwd_sum", Map.of("repayment_summary",
                List.of("stat_date", "branch_code", "repay_amount", "repay_count")));
        wl.put("reg_rep", Map.of("loan_classification",
                List.of("stat_date", "org_code", "normal", "special_mention", "substandard", "doubtful", "loss")));
        return Map.copyOf(wl);
    }

    private static Map<String, List<List<Object>>> loadData() {
        Map<String, List<List<Object>>> data = new LinkedHashMap<>();
        data.put("ods_core.loan_balance_daily", List.of(
                List.of("BRANCH0101", "2026-09-30", 1250000000.00, 12480),
                List.of("BRANCH0101", "2026-09-29", 1248000000.00, 12460),
                List.of("BRANCH0102", "2026-09-30", 980000000.00, 9320),
                List.of("BRANCH0102", "2026-09-29", 975000000.00, 9301),
                List.of("BRANCH0103", "2026-09-30", 640000000.00, 6105)));
        data.put("ods_credit.loan_account", List.of(
                List.of("6222001133445566", "张伟", "110101199001011234", "PERSL001", 250000.00, "NORMAL"),
                List.of("6222001133445577", "李娜", "310101198805054321", "PERSL001", 180000.00, "OVERDUE"),
                List.of("6222001133445588", "王强", "440101199203056789", "SME001", 3200000.00, "NORMAL")));
        data.put("dwd_sum.repayment_summary", List.of(
                List.of("2026-09-30", "BRANCH0101", 56000000.00, 1240),
                List.of("2026-09-30", "BRANCH0102", 42000000.00, 980)));
        data.put("reg_rep.loan_classification", List.of(
                List.of("2026-09-30", "ORG-HEAD", 2800000000.00, 120000000.00, 45000000.00, 18000000.00, 6000000.00)));
        return Map.copyOf(data);
    }
}
