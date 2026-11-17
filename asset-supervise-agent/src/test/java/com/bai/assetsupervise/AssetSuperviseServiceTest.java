package com.bai.assetsupervise;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AssetSuperviseService 单元测试：登记/采集/盯市（LTV 分档判定）/监控/预警/重估/查询/错误码/幂等。
 * 覆盖设计文档 4.2~4.7 接口、6.3 算法、7.x 阈值规则与 TC-01~TC-10 关键用例。
 */
class AssetSuperviseServiceTest {

    private final AssetSuperviseService service = new AssetSuperviseService();

    private static final String NOW = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    private static final String STALE = LocalDateTime.now().minusHours(25).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);

    private AssetSuperviseDtos.ObjectResult register(String objectType, String rid) {
        return service.registerObject(new AssetSuperviseDtos.ObjectRequest(null, "CREATE", objectType,
                "COL001", "LN001",
                Map.of("expectedQuantity", 1000, "loanValuation", 1000000), rid), rid);
    }

    // ------------------------------------------------------------ 登记

    @Test
    void 监管对象登记_创建更新终止状态流转() {
        AssetSuperviseDtos.ObjectResult created = register("MOVABLE", "reg-1");
        assertEquals("ACTIVE", created.status());
        assertNotNull(created.objectId());
        assertNotNull(created.registerNo());

        AssetSuperviseDtos.ObjectResult updated = service.registerObject(
                new AssetSuperviseDtos.ObjectRequest(created.objectId(), "UPDATE", "MOVABLE",
                        "COL001", "LN001", Map.of(), "reg-2"), "reg-2");
        assertEquals("CHANGED", updated.status());

        AssetSuperviseDtos.ObjectResult terminated = service.registerObject(
                new AssetSuperviseDtos.ObjectRequest(created.objectId(), "TERMINATE", "MOVABLE",
                        "COL001", "LN001", Map.of(), "reg-3"), "reg-3");
        assertEquals("TERMINATED", terminated.status());
        // 已终止对象再次操作 → ASS_4002
        BaiException e = assertThrows(BaiException.class,
                () -> service.collect(new AssetSuperviseDtos.CollectRequest(created.objectId(), "IOT",
                        List.of(new AssetSuperviseDtos.CollectMetric("QUANTITY", new BigDecimal("1000"), NOW)), null,
                        "reg-4"), "reg-4"));
        assertEquals("ASS_4002", e.getCode());
        // 登记缺关联标识（loanNo 缺失）→ ASS_4003
        BaiException ref = assertThrows(BaiException.class, () -> service.registerObject(
                new AssetSuperviseDtos.ObjectRequest(null, "CREATE", "MOVABLE", "COL001", null,
                        Map.of(), "reg-5"), "reg-5"));
        assertEquals("ASS_4003", ref.getCode());
    }

    // ------------------------------------------------------------ 采集

    @Test
    void 监测数据采集_质量报告与接收条数() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "col-1");
        AssetSuperviseDtos.CollectResult r = service.collect(new AssetSuperviseDtos.CollectRequest(
                o.objectId(), "IOT",
                List.of(new AssetSuperviseDtos.CollectMetric("QUANTITY", new BigDecimal("1000"), NOW),
                        new AssetSuperviseDtos.CollectMetric("TEMPERATURE", new BigDecimal("25"), NOW)),
                "raw-001", "col-2"), "col-2");
        assertEquals(2, r.accepted());
        assertEquals(2, r.quality().total());
        assertEquals(2, r.quality().valid());
        assertEquals(0, r.quality().abnormalRate().compareTo(BigDecimal.ZERO));
        assertNull(r.disconnected());
        assertNotNull(r.collectNo());
    }

    @Test
    void 监测数据采集_质量过低抛ASS4101() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "col-q");
        BaiException e = assertThrows(BaiException.class, () -> service.collect(new AssetSuperviseDtos.CollectRequest(
                o.objectId(), "IOT",
                List.of(new AssetSuperviseDtos.CollectMetric("QUANTITY", new BigDecimal("1000"), NOW),
                        new AssetSuperviseDtos.CollectMetric(null, new BigDecimal("1"), NOW),
                        new AssetSuperviseDtos.CollectMetric("STATUS", null, NOW)),
                null, "col-q2"), "col-q2"));
        assertEquals("ASS_4101", e.getCode());
    }

    @Test
    void 监测数据采集_断连超24小时标记REGULATORY_LOSS() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "col-d");
        AssetSuperviseDtos.CollectResult r = service.collect(new AssetSuperviseDtos.CollectRequest(
                o.objectId(), "IOT",
                List.of(new AssetSuperviseDtos.CollectMetric("QUANTITY", new BigDecimal("1000"), STALE)),
                null, "col-d2"), "col-d2");
        assertEquals("REGULATORY_LOSS", r.disconnected());
    }

    // ------------------------------------------------------------ 监控

    @Test
    void 实时监控_动产数量偏差超2pct识别异常() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "mon-1");
        // 实际 1050 vs 应存 1000 → 偏差 +5% > ±2% → 数量异常
        service.collect(new AssetSuperviseDtos.CollectRequest(o.objectId(), "IOT",
                List.of(new AssetSuperviseDtos.CollectMetric("QUANTITY", new BigDecimal("1050"), NOW)),
                null, "mon-2"), "mon-2");
        AssetSuperviseDtos.MonitorResult r = service.monitor(
                new AssetSuperviseDtos.MonitorRequest(o.objectId(), "MOVABLE", "DAILY", "v1", "mon-3"), "mon-3");
        assertEquals("ABNORMAL", r.conclusion());
        assertEquals(1, r.abnormals().size());
        assertEquals("QUANTITY_ABNORMAL", r.abnormals().get(0).type());
        assertEquals(0, new BigDecimal("0.05").compareTo(r.abnormals().get(0).deviation()));
    }

    @Test
    void 实时监控_不动产查封状态异动识别() {
        AssetSuperviseDtos.ObjectResult o = service.registerObject(new AssetSuperviseDtos.ObjectRequest(
                null, "CREATE", "REAL_ESTATE", "COL002", "LN002",
                Map.of("ownership", "张三", "location", "上海"), "mon-re-1"), "mon-re-1");
        service.collect(new AssetSuperviseDtos.CollectRequest(o.objectId(), "REGISTRY",
                List.of(new AssetSuperviseDtos.CollectMetric("STATUS", BigDecimal.ONE, NOW)), null, "mon-re-2"),
                "mon-re-2");
        AssetSuperviseDtos.MonitorResult r = service.monitor(
                new AssetSuperviseDtos.MonitorRequest(o.objectId(), "REAL_ESTATE", "MONTHLY", "v1", "mon-re-3"),
                "mon-re-3");
        assertEquals("ABNORMAL", r.conclusion());
        assertEquals("STATUS_CHANGE", r.abnormals().get(0).type());
    }

    @Test
    void 实时监控_数据断连超期抛ASS4103() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "mon-l");
        service.collect(new AssetSuperviseDtos.CollectRequest(o.objectId(), "IOT",
                List.of(new AssetSuperviseDtos.CollectMetric("QUANTITY", new BigDecimal("1000"), STALE)),
                null, "mon-l2"), "mon-l2");
        BaiException e = assertThrows(BaiException.class, () -> service.monitor(
                new AssetSuperviseDtos.MonitorRequest(o.objectId(), "MOVABLE", "DAILY", "v1", "mon-l3"), "mon-l3"));
        assertEquals("ASS_4103", e.getCode());
    }

    // ------------------------------------------------------------ LTV 盯市

    @Test
    void LTV_动产分档判定_NORMAL_WARNING_DISPOSAL() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "ltv-1");
        // 动产默认线 0.7/0.8：0.65 → NORMAL
        AssetSuperviseDtos.LtvResult normal = service.ltv(new AssetSuperviseDtos.LtvRequest(
                o.objectId(), new BigDecimal("1000000"), new BigDecimal("650000"), null, "DAILY",
                "2026-11-10T10:00", "ltv-2"), "ltv-2");
        assertEquals("NORMAL", normal.status());
        assertEquals(0, new BigDecimal("0.6500").compareTo(normal.ltv()));
        assertEquals(0, new BigDecimal("0.70").compareTo(normal.warningLine()));
        assertEquals(0, new BigDecimal("0.80").compareTo(normal.disposalLine()));
        assertEquals("NONE", normal.trend());
        // 0.72 越预警线 → WARNING
        AssetSuperviseDtos.LtvResult warning = service.ltv(new AssetSuperviseDtos.LtvRequest(
                o.objectId(), new BigDecimal("1000000"), new BigDecimal("720000"), null, "DAILY",
                "2026-11-10T10:30", "ltv-3"), "ltv-3");
        assertEquals("WARNING", warning.status());
        assertEquals("UP", warning.trend());
        // 0.85 越处置线 → DISPOSAL
        AssetSuperviseDtos.LtvResult disposal = service.ltv(new AssetSuperviseDtos.LtvRequest(
                o.objectId(), new BigDecimal("1000000"), new BigDecimal("850000"), null, "DAILY",
                "2026-11-10T11:00", "ltv-4"), "ltv-4");
        assertEquals("DISPOSAL", disposal.status());
    }

    @Test
    void LTV_不动产与权利分档判定() {
        // 不动产默认线 0.75/0.85：0.78 → WARNING
        AssetSuperviseDtos.ObjectResult re = service.registerObject(new AssetSuperviseDtos.ObjectRequest(
                null, "CREATE", "REAL_ESTATE", "COL003", "LN003", Map.of(), "ltv-re-1"), "ltv-re-1");
        AssetSuperviseDtos.LtvResult reResult = service.ltv(new AssetSuperviseDtos.LtvRequest(
                re.objectId(), new BigDecimal("1000000"), new BigDecimal("780000"), null, "MONTHLY",
                "2026-11-10T09:00", "ltv-re-2"), "ltv-re-2");
        assertEquals("WARNING", reResult.status());
        assertEquals(0, new BigDecimal("0.75").compareTo(reResult.warningLine()));
        assertEquals(0, new BigDecimal("0.85").compareTo(reResult.disposalLine()));
        // 权利（仓单）默认线 0.8/0.9：0.85 → WARNING
        AssetSuperviseDtos.ObjectResult wr = service.registerObject(new AssetSuperviseDtos.ObjectRequest(
                null, "CREATE", "WAREHOUSE_RECEIPT", "COL004", "LN004", Map.of(), "ltv-wr-1"), "ltv-wr-1");
        AssetSuperviseDtos.LtvResult wrResult = service.ltv(new AssetSuperviseDtos.LtvRequest(
                wr.objectId(), new BigDecimal("1000000"), new BigDecimal("850000"), null, "EVENT",
                "2026-11-10T09:00", "ltv-wr-2"), "ltv-wr-2");
        assertEquals("WARNING", wrResult.status());
        assertEquals(0, new BigDecimal("0.80").compareTo(wrResult.warningLine()));
        // 显式分档线覆盖：0.5/0.6，LTV 0.55 → WARNING
        AssetSuperviseDtos.LtvResult custom = service.ltv(new AssetSuperviseDtos.LtvRequest(
                wr.objectId(), new BigDecimal("1000000"), new BigDecimal("550000"),
                new AssetSuperviseDtos.LtvLines(new BigDecimal("0.5"), new BigDecimal("0.6")), "EVENT",
                "2026-11-10T10:00", "ltv-wr-3"), "ltv-wr-3");
        assertEquals("WARNING", custom.status());
    }

    @Test
    void LTV_估值缺失抛ASS4102() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "ltv-v");
        BaiException e = assertThrows(BaiException.class, () -> service.ltv(new AssetSuperviseDtos.LtvRequest(
                o.objectId(), null, new BigDecimal("650000"), null, "DAILY", null, "ltv-v2"), "ltv-v2"));
        assertEquals("ASS_4102", e.getCode());
    }

    @Test
    void LTV_幂等objectId加时间戳() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "ltv-id");
        AssetSuperviseDtos.LtvRequest first = new AssetSuperviseDtos.LtvRequest(
                o.objectId(), new BigDecimal("1000000"), new BigDecimal("650000"), null, "DAILY",
                "2026-11-10T10:00", "ltv-id-2");
        AssetSuperviseDtos.LtvResult r1 = service.ltv(first, "ltv-id-2");
        // 同 objectId+时间戳（不同 requestId）→ 返回首次结果（同 ltvNo）
        AssetSuperviseDtos.LtvResult r1b = service.ltv(first, "ltv-id-2b");
        assertEquals(r1.ltvNo(), r1b.ltvNo());
        // 不同时间戳 → 新记录
        AssetSuperviseDtos.LtvResult r2 = service.ltv(new AssetSuperviseDtos.LtvRequest(
                o.objectId(), new BigDecimal("1000000"), new BigDecimal("700000"), null, "DAILY",
                "2026-11-10T11:00", "ltv-id-3"), "ltv-id-3");
        assertNotEquals(r1.ltvNo(), r2.ltvNo());
        assertEquals("UP", r2.trend());
        // 同 objectId+时间戳但参数不同 → ASS_4004 幂等冲突
        BaiException e = assertThrows(BaiException.class, () -> service.ltv(new AssetSuperviseDtos.LtvRequest(
                o.objectId(), new BigDecimal("1000000"), new BigDecimal("900000"), null, "DAILY",
                "2026-11-10T10:00", "ltv-id-4"), "ltv-id-4"));
        assertEquals("ASS_4004", e.getCode());
    }

    // ------------------------------------------------------------ 预警

    @Test
    void 预警_等级与处置任务生成() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "al-1");
        // A01 价值跌穿 → WARNING + 追加担保 客户经理 7 日
        AssetSuperviseDtos.AlertResult breach = service.alert(new AssetSuperviseDtos.AlertRequest(
                o.objectId(), "VALUE_BREACH", "实时估值较放款估值下跌 12%", "v1", "al-2"), "al-2");
        assertEquals("WARNING", breach.level());
        assertEquals("追加担保/补足差额", breach.tasks().get(0).taskType());
        assertEquals("客户经理", breach.tasks().get(0).assignee());
        // A03 监管失效 → SEVERE + 现场核查 24h 内
        AssetSuperviseDtos.AlertResult loss = service.alert(new AssetSuperviseDtos.AlertRequest(
                o.objectId(), "REGULATORY_LOSS", "数据断连 25h", "v1", "al-3"), "al-3");
        assertEquals("SEVERE", loss.level());
        assertEquals("现场核查+暂停用信", loss.tasks().get(0).taskType());
        // A06 状态异动 → SEVERE + 法律处置协同 即时
        AssetSuperviseDtos.AlertResult status = service.alert(new AssetSuperviseDtos.AlertRequest(
                o.objectId(), "STATUS_CHANGE", "权属变更/查封", "v1", "al-4"), "al-4");
        assertEquals("SEVERE", status.level());
        assertEquals("法律处置协同", status.tasks().get(0).taskType());
        // A05 盯市超处置线（evidence 含 DISPOSAL）→ SEVERE + 处置变现
        AssetSuperviseDtos.AlertResult over = service.alert(new AssetSuperviseDtos.AlertRequest(
                o.objectId(), "LTV_OVER", "LTV 0.85 DISPOSAL", "v1", "al-5"), "al-5");
        assertEquals("SEVERE", over.level());
        assertEquals("处置变现/解除合作", over.tasks().get(0).taskType());
        assertNotNull(over.alertId());
    }

    @Test
    void 预警_策略版本不存在抛ASS4104() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "al-v");
        BaiException e = assertThrows(BaiException.class, () -> service.alert(new AssetSuperviseDtos.AlertRequest(
                o.objectId(), "LTV_OVER", "LTV 0.72", "v2", "al-v2"), "al-v2"));
        assertEquals("ASS_4104", e.getCode());
    }

    // ------------------------------------------------------------ 重估

    @Test
    void 重估_过期触发结果回流与未到期不重估() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "rv-1");
        // 估值缺失（valuationTime=null）→ PERIODIC 触发重估并回流
        AssetSuperviseDtos.RevalueResult r1 = service.revalue(new AssetSuperviseDtos.RevalueRequest(
                o.objectId(), "PERIODIC", Map.of("valuation", 800000), "rv-2"), "rv-2");
        assertTrue(r1.benchmarkUpdated());
        assertEquals(0, new BigDecimal("800000").compareTo(r1.valuationResult()));
        assertNotNull(r1.revalueTaskId());
        // 重估后估值新鲜 → PERIODIC 不再重估
        AssetSuperviseDtos.RevalueResult r2 = service.revalue(new AssetSuperviseDtos.RevalueRequest(
                o.objectId(), "PERIODIC", Map.of("valuation", 900000), "rv-3"), "rv-3");
        assertFalse(r2.benchmarkUpdated());
        assertEquals(0, new BigDecimal("800000").compareTo(r2.valuationResult()));
        // ALERT 触发须附新估值，缺失抛 ASS_4102
        BaiException e = assertThrows(BaiException.class, () -> service.revalue(new AssetSuperviseDtos.RevalueRequest(
                o.objectId(), "ALERT", Map.of(), "rv-4"), "rv-4"));
        assertEquals("ASS_4102", e.getCode());
        // ALERT 触发且附新估值 → 强制重估回流
        AssetSuperviseDtos.RevalueResult r3 = service.revalue(new AssetSuperviseDtos.RevalueRequest(
                o.objectId(), "ALERT", Map.of("valuation", 750000), "rv-5"), "rv-5");
        assertTrue(r3.benchmarkUpdated());
        assertEquals(0, new BigDecimal("750000").compareTo(r3.valuationResult()));
    }

    // ------------------------------------------------------------ 查询 / 幂等

    @Test
    void 查询_记录命中与幂等冲突() {
        AssetSuperviseDtos.ObjectResult o = register("MOVABLE", "q-1");
        service.ltv(new AssetSuperviseDtos.LtvRequest(o.objectId(), new BigDecimal("1000000"),
                new BigDecimal("650000"), null, "DAILY", "2026-11-10T10:00", "q-2"), "q-2");
        service.ltv(new AssetSuperviseDtos.LtvRequest(o.objectId(), new BigDecimal("1000000"),
                new BigDecimal("850000"), null, "DAILY", "2026-11-10T11:00", "q-3"), "q-3");
        service.alert(new AssetSuperviseDtos.AlertRequest(o.objectId(), "LTV_OVER", "LTV 0.85 DISPOSAL",
                "v1", "q-4"), "q-4");

        // 盯市记录查询：按 objectId 命中 2 条
        AssetSuperviseDtos.QueryResult ltvQuery = service.query("LTV", o.objectId(), null, 1, 20, "q-5");
        assertEquals(2, ltvQuery.total());
        // 预警记录查询：命中 1 条
        AssetSuperviseDtos.QueryResult alertQuery = service.query("ALERT", o.objectId(), null, 1, 20, "q-6");
        assertEquals(1, alertQuery.total());
        // 监管对象台账查询：状态 ACTIVE
        AssetSuperviseDtos.QueryResult objQuery = service.query("OBJECT", o.objectId(), null, 1, 20, "q-7");
        assertEquals(1, objQuery.total());
        assertEquals("ACTIVE", ((AssetSuperviseDtos.SupervisedObjectView) objQuery.items().get(0)).status());

        // 幂等：同 requestId 同参数 → 返回首次登记结果（registerNo 一致）
        AssetSuperviseDtos.ObjectResult again = service.registerObject(new AssetSuperviseDtos.ObjectRequest(
                null, "CREATE", "MOVABLE", "COL001", "LN001",
                Map.of("expectedQuantity", 1000, "loanValuation", 1000000), "q-1"), "q-1");
        assertEquals(o.registerNo(), again.registerNo());
        // 幂等冲突：同 requestId 不同参数 → ASS_4004
        BaiException e = assertThrows(BaiException.class, () -> service.registerObject(
                new AssetSuperviseDtos.ObjectRequest(null, "CREATE", "MOVABLE", "COL001", "LN001",
                        Map.of("expectedQuantity", 2000, "loanValuation", 1000000), "q-1"), "q-1"));
        assertEquals("ASS_4004", e.getCode());
        // 监管对象不存在 → ASS_4002
        BaiException notFound = assertThrows(BaiException.class,
                () -> service.query("OBJECT", "NO-SUCH-OBJ", null, 1, 20, "q-8"));
        assertEquals("ASS_4002", notFound.getCode());
    }
}
