package com.bai.dataquery;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataQueryService 单元测试：统一数据查询（内存仿真）——白名单校验、条件过滤排序、
 * 列级脱敏、分组聚合、NL2SQL、注入拒绝、幂等。
 */
class DataQueryServiceTest {

    private final DataQueryService service = new DataQueryService();

    @Test
    void 条件查询_过滤排序返回确定行() {
        DataQueryDtos.QueryRequest req = new DataQueryDtos.QueryRequest(null, "ods_core", "loan_balance_daily",
                List.of("branch_code", "loan_balance"),
                List.of(new DataQueryDtos.Condition("branch_code", "=", "BRANCH0101")),
                null, new DataQueryDtos.OrderBy("loan_balance", "DESC"), null);
        DataQueryDtos.QueryResult r = service.query(req, "dq-ok1");
        assertEquals(2, r.rowCount());
        assertFalse(r.masked());
        assertEquals(1_250_000_000.0, ((Number) r.rows().get(0).get(1)).doubleValue());
        assertEquals("BRANCH0101", r.rows().get(0).get(0));
        assertTrue(r.sql().contains("WHERE branch_code = ?"));
        assertTrue(r.sql().contains("ORDER BY loan_balance DESC"));
    }

    @Test
    void 敏感列查询_脱敏生效() {
        DataQueryDtos.QueryRequest req = new DataQueryDtos.QueryRequest(null, "ods_credit", "loan_account",
                List.of("cust_name", "id_no", "account_no"), null, null, null, null);
        DataQueryDtos.QueryResult r = service.query(req, "dq-m1");
        assertTrue(r.masked());
        assertEquals(3, r.rowCount());
        assertEquals("张*", r.rows().get(0).get(0));
        assertEquals("110101********1234", r.rows().get(0).get(1));
        assertEquals("************5566", r.rows().get(0).get(2));
    }

    @Test
    void 分组聚合_按机构求和() {
        DataQueryDtos.QueryRequest req = new DataQueryDtos.QueryRequest(null, "dwd_sum", "repayment_summary",
                List.of("branch_code", "repay_amount"), null, List.of("branch_code"), null, null);
        DataQueryDtos.QueryResult r = service.query(req, "dq-g1");
        assertEquals(2, r.rowCount());
    }

    @Test
    void NL查询_生成SQL并校验通过() {
        // NL 路由仅按关键词选表，数据源需显式指定（repayment_summary 位于 dwd_sum）
        DataQueryDtos.NlResult r = service.nl(
                new DataQueryDtos.NlRequest(null, "查询各分支还款情况", "dwd_sum", null), "dq-nl1");
        assertTrue(r.validated());
        assertTrue(r.generatedSql().contains("repayment_summary"));
        assertEquals(2, r.rowCount());
        assertNotNull(r.queryId());
    }

    @Test
    void NL写意图_抛DTQ1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.nl(new DataQueryDtos.NlRequest(null, "删除所有数据", null, null), "dq-nl2"));
        assertEquals("DTQ_1006", e.getCode());
    }

    @Test
    void 数据源不存在_抛DTQ1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(new DataQueryDtos.QueryRequest(null, "ods_hack", "t",
                        List.of("a"), null, null, null, null), "dq-e1"));
        assertEquals("DTQ_1004", e.getCode());
    }

    @Test
    void 非白名单列_抛DTQ1003() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(new DataQueryDtos.QueryRequest(null, "ods_core", "loan_balance_daily",
                        List.of("password"), null, null, null, null), "dq-e2"));
        assertEquals("DTQ_1003", e.getCode());
    }

    @Test
    void 注入条件值_抛DTQ1003() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(new DataQueryDtos.QueryRequest(null, "ods_core", "loan_balance_daily",
                        List.of("branch_code"),
                        List.of(new DataQueryDtos.Condition("branch_code", "=", "x' OR '1'='1")),
                        null, null, null), "dq-e3"));
        assertEquals("DTQ_1003", e.getCode());
    }

    @Test
    void 不支持操作符_抛DTQ1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.query(new DataQueryDtos.QueryRequest(null, "ods_core", "loan_balance_daily",
                        List.of("branch_code"),
                        List.of(new DataQueryDtos.Condition("branch_code", "XOR", "1")),
                        null, null, null), "dq-e4"));
        assertEquals("DTQ_1001", e.getCode());
    }

    @Test
    void 同requestId_返回首次结果() {
        String rid = "dq-idem";
        DataQueryDtos.QueryRequest req = new DataQueryDtos.QueryRequest(null, "ods_core", "loan_balance_daily",
                List.of("branch_code", "loan_balance"), null, null, null, null);
        DataQueryDtos.QueryResult a = service.query(req, rid);
        DataQueryDtos.QueryResult b = service.query(req, rid);
        assertEquals(a.queryId(), b.queryId());
    }

    @Test
    void 数据源列表_白名单快照非空() {
        DataQueryDtos.DatasourcesResult r = service.datasources();
        assertEquals("2026.09.01", r.version());
        assertFalse(r.datasources().isEmpty());
        assertTrue(r.datasources().stream().allMatch(DataQueryDtos.DatasourceInfo::readOnly));
    }
}
