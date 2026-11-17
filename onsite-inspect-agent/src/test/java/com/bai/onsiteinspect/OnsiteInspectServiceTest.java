package com.bai.onsiteinspect;

import com.bai.common.BaiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OnsiteInspectService 单元测试：下户任务创建（双人成行/预约/提纲）、状态机 PENDING→BOOKED→IN_PROGRESS→COMPLETED、
 * 查勘清单（五看/抵押物点位）、证据采集（时间位置一致性/幂等）、访谈采集（双录授权）、现场结论生成（五看结论/异常）、
 * 错误码（OIS_4001/4002/4003/4004/4101/4102/4103/4104）与幂等冲突。
 */
class OnsiteInspectServiceTest {

    private OnsiteInspectService service;

    @BeforeEach
    void setUp() {
        service = new OnsiteInspectService();
    }

    // ------------------------------------------------------------ 工具

    private Map<String, Object> taskBody(String action, String taskId, String customerId, String inspectType,
                                         String loanNo, List<String> inspectors, Map<String, Object> appointment,
                                         String version) {
        Map<String, Object> m = new HashMap<>();
        m.put("action", action);
        if (taskId != null) m.put("taskId", taskId);
        if (customerId != null) m.put("customerId", customerId);
        if (inspectType != null) m.put("inspectType", inspectType);
        if (loanNo != null) m.put("loanNo", loanNo);
        if (inspectors != null) m.put("inspectors", inspectors);
        if (appointment != null) m.put("appointment", appointment);
        if (version != null) m.put("checklistVersion", version);
        return m;
    }

    private Map<String, Object> appointment(String time, String location) {
        Map<String, Object> apt = new HashMap<>();
        if (time != null) apt.put("time", time);
        if (location != null) apt.put("location", location);
        return apt;
    }

    /** 已预约任务（2026-11-10 10:00 @ 北京 116.40,39.90），用于证据/访谈/结论用例 */
    private OnsiteInspectDtos.TaskView bookedTask(String requestId) {
        return service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), appointment("2026-11-10T10:00:00", "116.40,39.90"), "V1.0"), requestId);
    }

    private Map<String, Object> evidenceBody(String taskId, String type, String mediaRef, String gps,
                                             String captureTime, String note) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", taskId);
        m.put("evidenceType", type);
        m.put("mediaRef", mediaRef);
        if (gps != null) m.put("gps", gps);
        if (captureTime != null) m.put("captureTime", captureTime);
        if (note != null) m.put("note", note);
        return m;
    }

    private Map<String, Object> interviewBody(String taskId, String layer, List<String> questions,
                                              String recordingRef, String authRef, String transcript) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", taskId);
        m.put("layer", layer);
        if (questions != null) m.put("questions", questions);
        if (recordingRef != null) m.put("recordingRef", recordingRef);
        if (authRef != null) m.put("authorizationRef", authRef);
        if (transcript != null) m.put("transcript", transcript);
        return m;
    }

    private Map<String, Object> summaryBody(String taskId, Map<String, Object> fiveViews, String collateral,
                                            List<Map<String, Object>> abnormals, String templateVersion) {
        Map<String, Object> m = new HashMap<>();
        m.put("taskId", taskId);
        if (fiveViews != null) m.put("fiveViews", fiveViews);
        if (collateral != null) m.put("collateralConclusion", collateral);
        if (abnormals != null) m.put("abnormals", abnormals);
        if (templateVersion != null) m.put("templateVersion", templateVersion);
        return m;
    }

    private Map<String, Object> fiveViews(String... values) {
        Map<String, Object> m = new HashMap<>();
        String[] keys = {"生产", "库存", "人", "单据", "环境"};
        for (int i = 0; i < values.length; i++) {
            m.put(keys[i], values[i]);
        }
        return m;
    }

    // ------------------------------------------------------------ 任务创建

    @Test
    void 创建任务_预约BOOKED_双人成行_提纲生成() {
        OnsiteInspectDtos.TaskView v = service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), appointment("2026-11-10T10:00:00", "116.40,39.90"), "V1.0"), "ois-1");
        assertTrue(v.taskId().startsWith("OIT"));
        assertEquals("BOOKED", v.status());
        assertTrue(v.inspectorsChecked());
        assertEquals(List.of("u1", "u2"), v.inspectors());
        assertNotNull(v.outline());
        assertEquals("OUTL-PRE_LOAN-V1.0", v.outline().outlineRef());
        assertFalse(v.outline().points().isEmpty());
    }

    @Test
    void 创建任务_无预约_状态PENDING() {
        OnsiteInspectDtos.TaskView v = service.task(taskBody("CREATE", null, "C1001", "POST_LOAN", "L1001",
                List.of("u1", "u2"), null, null), "ois-2");
        assertEquals("PENDING", v.status());
    }

    @Test
    void 双人不足与客户贷款不存在_抛OIS4101_OIS4003() {
        // 仅一名调查人 → OIS_4101 双人未成行
        BaiException e1 = assertThrows(BaiException.class, () -> service.task(
                taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001", List.of("u1"), null, null), "ois-3a"));
        assertEquals("OIS_4101", e1.getCode());
        // 客户标识非法 → OIS_4003
        BaiException e2 = assertThrows(BaiException.class, () -> service.task(
                taskBody("CREATE", null, "X999", "PRE_LOAN", "L1001", List.of("u1", "u2"), null, null), "ois-3b"));
        assertEquals("OIS_4003", e2.getCode());
        // 贷款标识非法 → OIS_4003
        BaiException e3 = assertThrows(BaiException.class, () -> service.task(
                taskBody("CREATE", null, "C1001", "PRE_LOAN", "bad-loan", List.of("u1", "u2"), null, null), "ois-3c"));
        assertEquals("OIS_4003", e3.getCode());
        // 贷前缺 loanNo → OIS_4001
        BaiException e4 = assertThrows(BaiException.class, () -> service.task(
                taskBody("CREATE", null, "C1001", "PRE_LOAN", null, List.of("u1", "u2"), null, null), "ois-3d"));
        assertEquals("OIS_4001", e4.getCode());
    }

    // ------------------------------------------------------------ 状态机

    @Test
    void 状态机_预约开始完成_状态只进不退() {
        OnsiteInspectDtos.TaskView created = service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), null, null), "ois-4a");
        String id = created.taskId();
        assertEquals("PENDING", created.status());
        assertEquals("BOOKED", service.task(taskBody("UPDATE", id, null, null, null, null,
                appointment("2026-11-10T10:00:00", "116.40,39.90"), null), "ois-4b").status());
        assertEquals("IN_PROGRESS", service.task(taskBody("START", id, null, null, null, null, null, null), "ois-4c").status());
        assertEquals("COMPLETED", service.task(taskBody("COMPLETE", id, null, null, null, null, null, null), "ois-4d").status());
    }

    @Test
    void 状态机_非法流转_抛OIS4006() {
        OnsiteInspectDtos.TaskView v = service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), null, null), "ois-5a");
        String id = v.taskId();
        // 未 START 直接 COMPLETE → OIS_4006
        BaiException e1 = assertThrows(BaiException.class, () -> service.task(
                taskBody("COMPLETE", id, null, null, null, null, null, null), "ois-5b"));
        assertEquals("OIS_4006", e1.getCode());
        service.task(taskBody("START", id, null, null, null, null, null, null), "ois-5c");
        // 重复 START → OIS_4006
        BaiException e2 = assertThrows(BaiException.class, () -> service.task(
                taskBody("START", id, null, null, null, null, null, null), "ois-5d"));
        assertEquals("OIS_4006", e2.getCode());
    }

    @Test
    void 任务缺失与终态操作_抛OIS4002() {
        // 任务不存在
        BaiException e1 = assertThrows(BaiException.class, () -> service.task(
                taskBody("ABORT", "OIT-NOT-FOUND", null, null, null, null, null, null), "ois-6a"));
        assertEquals("OIS_4002", e1.getCode());
        // 完成后 ABORT → 已终止
        OnsiteInspectDtos.TaskView v = service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), null, null), "ois-6b");
        String id = v.taskId();
        service.task(taskBody("START", id, null, null, null, null, null, null), "ois-6c");
        service.task(taskBody("COMPLETE", id, null, null, null, null, null, null), "ois-6d");
        BaiException e2 = assertThrows(BaiException.class, () -> service.task(
                taskBody("ABORT", id, null, null, null, null, null, null), "ois-6e"));
        assertEquals("OIS_4002", e2.getCode());
    }

    // ------------------------------------------------------------ 查勘清单

    @Test
    void 查勘清单_五看与抵押物点位_版本默认与校验() {
        OnsiteInspectDtos.ChecklistResult five = service.checklist("PRE_LOAN", null, null);
        assertEquals("V1.0", five.version());
        assertEquals("OUTL-PRE_LOAN-V1.0", five.outlineRef());
        assertEquals(5, five.items().size());
        assertTrue(five.items().stream().allMatch(OnsiteInspectDtos.ChecklistItem::required));

        OnsiteInspectDtos.ChecklistResult coll = service.checklist("COLLATERAL", "不动产", "V1.0");
        assertTrue(coll.items().stream().anyMatch(i -> i.name().contains("权属")));
        assertTrue(coll.items().stream().anyMatch(i -> i.name().contains("价值相符性")));

        // COLLATERAL 缺 collateralType → OIS_4001；版本不存在 → OIS_4001
        BaiException e1 = assertThrows(BaiException.class, () -> service.checklist("COLLATERAL", null, null));
        assertEquals("OIS_4001", e1.getCode());
        BaiException e2 = assertThrows(BaiException.class, () -> service.checklist("PRE_LOAN", null, "V9.9"));
        assertEquals("OIS_4001", e2.getCode());
        BaiException e3 = assertThrows(BaiException.class, () -> service.checklist("COLLATERAL", "船只", "V1.0"));
        assertEquals("OIS_4001", e3.getCode());
    }

    // ------------------------------------------------------------ 证据采集

    @Test
    void 证据采集_正常采集_质量HIGH_索引就绪() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-ev0");
        OnsiteInspectDtos.EvidenceResult r = service.evidence(
                evidenceBody(v.taskId(), "PHOTO", "M-001", "116.40,39.90", "2026-11-10T10:30:00", "厂区照片"), "ois-ev1");
        assertTrue(r.evidenceId().startsWith("EVI"));
        assertTrue(r.indexed());
        assertEquals("HIGH", r.quality());
        assertTrue(r.consistency());
    }

    @Test
    void 证据采集_时间位置超限_抛OIS4104() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-ev0b");
        // 拍摄时间超窗（预约 10:00，拍摄 15:00，超 ±2h）
        BaiException e1 = assertThrows(BaiException.class, () -> service.evidence(
                evidenceBody(v.taskId(), "PHOTO", "M-002", "116.40,39.90", "2026-11-10T15:00:00", null), "ois-ev2a"));
        assertEquals("OIS_4104", e1.getCode());
        // 拍摄位置超半径（北京 → 上海约 1067km > 5km）
        BaiException e2 = assertThrows(BaiException.class, () -> service.evidence(
                evidenceBody(v.taskId(), "PHOTO", "M-003", "121.47,31.23", "2026-11-10T10:30:00", null), "ois-ev2b"));
        assertEquals("OIS_4104", e2.getCode());
        // 证据类型非法 → OIS_4001
        BaiException e3 = assertThrows(BaiException.class, () -> service.evidence(
                evidenceBody(v.taskId(), "QRCODE", "M-004", null, null, null), "ois-ev2c"));
        assertEquals("OIS_4001", e3.getCode());
    }

    @Test
    void 证据采集_幂等taskId加mediaRef_参数不同抛OIS4004() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-ev0c");
        OnsiteInspectDtos.EvidenceResult a = service.evidence(
                evidenceBody(v.taskId(), "PHOTO", "M-010", "116.40,39.90", "2026-11-10T10:30:00", "相同参数"), "ois-ev3a");
        OnsiteInspectDtos.EvidenceResult b = service.evidence(
                evidenceBody(v.taskId(), "PHOTO", "M-010", "116.40,39.90", "2026-11-10T10:30:00", "相同参数"), "ois-ev3b");
        assertEquals(a.evidenceId(), b.evidenceId());
        // 同 taskId+mediaRef 不同参数 → OIS_4004 幂等冲突
        BaiException e = assertThrows(BaiException.class, () -> service.evidence(
                evidenceBody(v.taskId(), "VIDEO", "M-010", "116.40,39.90", "2026-11-10T10:30:00", "不同参数"), "ois-ev3c"));
        assertEquals("OIS_4004", e.getCode());
    }

    // ------------------------------------------------------------ 访谈采集

    @Test
    void 访谈采集_双录授权缺失_抛OIS4103() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-iv0");
        // 双录但无授权文件 → OIS_4103
        BaiException e1 = assertThrows(BaiException.class, () -> service.interview(
                interviewBody(v.taskId(), "BOSS", List.of("还款来源？"), "REC-001", null, null), "ois-iv1"));
        assertEquals("OIS_4103", e1.getCode());
        // questions 缺失 → OIS_4001
        BaiException e2 = assertThrows(BaiException.class, () -> service.interview(
                interviewBody(v.taskId(), "BOSS", null, "REC-001", "AUTH-001", null), "ois-iv1b"));
        assertEquals("OIS_4001", e2.getCode());
        // layer 非法 → OIS_4001
        BaiException e3 = assertThrows(BaiException.class, () -> service.interview(
                interviewBody(v.taskId(), "CEO", List.of("问题？"), null, null, null), "ois-iv1c"));
        assertEquals("OIS_4001", e3.getCode());
    }

    @Test
    void 访谈采集_三层访谈成功_双录登记() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-iv0b");
        OnsiteInspectDtos.InterviewResult r = service.interview(
                interviewBody(v.taskId(), "FINANCE", List.of("收入构成？", "应收账期？"), "REC-002", "AUTH-2026", null), "ois-iv2");
        assertTrue(r.interviewId().startsWith("IVW"));
        assertEquals("FINANCE", r.layer());
        assertTrue(r.recorded());
        assertEquals("TRAN-" + r.interviewId(), r.transcriptRef());   // 转写引用（asr 协同）
        assertTrue(r.notes().contains("已双录"));
    }

    // ------------------------------------------------------------ 现场结论

    @Test
    void 现场结论_五看全正常_NORMAL素材生成() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-sm0");
        OnsiteInspectDtos.SummaryResult r = service.summary(
                summaryBody(v.taskId(), fiveViews("NORMAL", "NORMAL", "NORMAL", "NORMAL", "NORMAL"), null, null, "V1.0"), "ois-sm1");
        assertEquals("NORMAL", r.conclusion());
        assertTrue(r.summaryId().startsWith("SUM"));
        assertEquals("MAT-" + r.summaryId(), r.materialRef());
        assertTrue(r.abnormals().isEmpty());
    }

    @Test
    void 现场结论_异常项证据缺失_抛OIS4102_补采后ABNORMAL() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-sm0b");
        Map<String, Object> fv = fiveViews("ABNORMAL", "NORMAL", "NORMAL", "NORMAL", "NORMAL");
        // 五看异常但未采集证据 → OIS_4102（异常项证据必须完整）
        BaiException e = assertThrows(BaiException.class,
                () -> service.summary(summaryBody(v.taskId(), fv, null, null, "V1.0"), "ois-sm2a"));
        assertEquals("OIS_4102", e.getCode());
        // 补采证据后 → ABNORMAL
        service.evidence(evidenceBody(v.taskId(), "PHOTO", "M-020", "116.40,39.90", "2026-11-10T10:30:00", null), "ois-sm2b");
        OnsiteInspectDtos.SummaryResult r = service.summary(
                summaryBody(v.taskId(), fv, null, null, "V1.0"), "ois-sm2c");
        assertEquals("ABNORMAL", r.conclusion());
    }

    @Test
    void 现场结论_严重异常与非法异常项() {
        OnsiteInspectDtos.TaskView v = bookedTask("ois-sm0c");
        Map<String, Object> fv = fiveViews("NORMAL", "NORMAL", "NORMAL", "NORMAL", "NORMAL");
        // 严重异常 → ABNORMAL
        OnsiteInspectDtos.SummaryResult r = service.summary(summaryBody(v.taskId(), fv, null,
                List.of(Map.of("type", "AB02", "level", "严重", "description", "查勘与台账不符")), "V1.0"), "ois-sm3");
        assertEquals("ABNORMAL", r.conclusion());
        assertEquals(1, r.abnormals().size());
        assertEquals("AB02", r.abnormals().get(0).type());
        assertEquals("严重", r.abnormals().get(0).level());
        // 关注 → ATTENTION
        Map<String, Object> fvAtt = fiveViews("NORMAL", "ATTENTION", "NORMAL", "NORMAL", "NORMAL");
        OnsiteInspectDtos.SummaryResult att = service.summary(
                summaryBody(v.taskId(), fvAtt, null, null, "V1.0"), "ois-sm3a");
        assertEquals("ATTENTION", att.conclusion());
        // 异常类型非法 → OIS_4001
        BaiException e = assertThrows(BaiException.class, () -> service.summary(summaryBody(v.taskId(), fv, null,
                List.of(Map.of("type", "AB99", "level", "严重")), "V1.0"), "ois-sm3b"));
        assertEquals("OIS_4001", e.getCode());
    }

    // ------------------------------------------------------------ 幂等

    @Test
    void 幂等_同requestId返回首次_参数不同抛OIS4004() {
        OnsiteInspectDtos.TaskView a = service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), null, null), "ois-idem");
        OnsiteInspectDtos.TaskView b = service.task(taskBody("CREATE", null, "C1001", "PRE_LOAN", "L1001",
                List.of("u1", "u2"), null, null), "ois-idem");
        assertEquals(a.taskId(), b.taskId());
        // 同 requestId 不同参数 → OIS_4004 幂等冲突
        BaiException e = assertThrows(BaiException.class, () -> service.task(
                taskBody("CREATE", null, "C1002", "PRE_LOAN", "L1001", List.of("u1", "u2"), null, null), "ois-idem"));
        assertEquals("OIS_4004", e.getCode());
    }
}
