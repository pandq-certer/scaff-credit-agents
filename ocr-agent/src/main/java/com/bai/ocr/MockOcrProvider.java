package com.bai.ocr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Mock 引擎：确定性伪识别（哈希驱动伪输出，不解码像素）。
 * <p>输出结构与真实引擎一致，便于下游联调；行为与改造前仿真版完全一致（回归基线）。
 */
public class MockOcrProvider implements OcrProvider {

    /** 演示姓名池（确定性轮转） */
    private static final String[] NAMES = {"张伟", "李娜", "王强", "刘芳", "陈静", "杨洋", "赵磊", "孙丽"};

    @Override
    public OcrDtos.OcrResult recognize(byte[] content, String filename, String docType, String requestId) {
        long start = System.currentTimeMillis();
        String type = docType == null || docType.isBlank() ? "GENERIC" : docType.toUpperCase();
        List<Double> bbox = List.of(0.05, 0.10, 0.95, 0.30);
        List<OcrDtos.Block> blocks = new ArrayList<>();
        blocks.add(new OcrDtos.Block("仿真识别文本行（未接入真实模型，仅用于联调）", 0.97, bbox, null, null));

        Map<String, OcrDtos.Field> fields = new LinkedHashMap<>();
        if ("ID_CARD".equals(type)) {
            fields.put("name", new OcrDtos.Field("张*", 0.99, null, null));
            fields.put("idNo", new OcrDtos.Field("1101**********0011", 0.98, null, null));
        } else if ("INVOICE".equals(type)) {
            fields.put("invoiceNo", new OcrDtos.Field("044032600011", 0.98, null, null));
            fields.put("amount", new OcrDtos.Field("1,280.00", 0.97, null, null));
        }

        long elapsed = System.currentTimeMillis() - start;
        return new OcrDtos.OcrResult(
                "OC" + UUID.randomUUID().toString().replace("-", "").substring(0, 16),
                type, "SUCCESS", 0.97, List.copyOf(blocks), fields, elapsed);
    }

    @Override
    public RefResult recognizeRef(String imageRef, String docType, String requestId) {
        Random rnd = new Random(imageRef.hashCode());
        Map<String, String> fields = docFields(docType, rnd);
        int h = Math.abs(imageRef.hashCode());
        double confidence = Math.round((0.90 + (h % 100) / 100.0 * 0.09) * 100.0) / 100.0;
        return new RefResult(fields, confidence);
    }

    /** 按 docType 内置确定性演示字段表（覆盖信贷场景 7 类单据 + 通用单据） */
    private Map<String, String> docFields(String docType, Random rnd) {
        Map<String, String> f = new LinkedHashMap<>();
        switch (docType) {
            case "ID_CARD" -> {
                f.put("姓名", NAMES[rnd.nextInt(NAMES.length)]);
                f.put("证件号", "1101" + (1900 + rnd.nextInt(100)) + String.format("%02d", 1 + rnd.nextInt(12))
                        + String.format("%02d", 1 + rnd.nextInt(28)) + String.format("%012d", Math.abs(rnd.nextLong()) % 1_000_000_000_000L));
                f.put("有效期", (2015 + rnd.nextInt(10)) + "-01-01~" + (2025 + rnd.nextInt(20)) + "-01-01");
            }
            case "AUTH_LETTER" -> {
                f.put("授权编号", "SQ" + String.format("%012d", Math.abs(rnd.nextLong()) % 1_000_000_000_000L));
                f.put("用途", of(rnd, "贷款申请", "担保授信", "征信查询", "业务办理"));
                f.put("有效期", (2026 + rnd.nextInt(3)) + "-12-31");
            }
            case "INVOICE" -> {
                f.put("发票号", String.format("%020d", Math.abs(rnd.nextLong()) % 1_000_000_000_000_000_000L));
                f.put("金额", money(rnd));
                f.put("销方", of(rnd, "华信贸易有限公司", "中恒实业有限公司", "泰达科技有限公司", "恒信商贸有限公司"));
            }
            case "PURCHASE_CONTRACT" -> {
                f.put("合同编号", "HT-" + (2024 + rnd.nextInt(3)) + "-" + String.format("%06d", rnd.nextInt(1_000_000)));
                f.put("买方", of(rnd, "华信贸易有限公司", "中恒实业有限公司", "泰达科技有限公司"));
                f.put("卖方", of(rnd, "恒信商贸有限公司", "蓝天物资有限公司", "宏图制造有限公司"));
                f.put("金额", money(rnd));
            }
            case "TICKET" -> {
                f.put("票号", String.format("%016d", Math.abs(rnd.nextLong()) % 9_000_000_000_000_000L + 1_000_000_000_000_000L));
                f.put("金额", money(rnd));
                f.put("到期日", (2026 + rnd.nextInt(2)) + "-" + String.format("%02d", 1 + rnd.nextInt(12)) + "-" + String.format("%02d", 1 + rnd.nextInt(28)));
                f.put("承兑人", of(rnd, "工商银行", "建设银行", "中国银行", "招商银行", "交通银行"));
            }
            case "CUSTOMS_DECL" -> {
                f.put("报关单号", String.format("%018d", Math.abs(rnd.nextLong()) % 1_000_000_000_000_000_000L));
                f.put("出口金额", "USD " + (10_000 + rnd.nextInt(990_000)) + ".00");
                f.put("贸易国", of(rnd, "美国", "德国", "日本", "韩国", "澳大利亚", "越南"));
            }
            case "TAX_DOC" -> {
                f.put("税号", "9111" + alnum(rnd, 14).toUpperCase());
                f.put("开票金额", money(rnd));
                f.put("红冲标志", of(rnd, "否", "是"));
            }
            default -> {
                // AUTO / MIXED_DOC / 其他：按 docType 哈希生成 4~6 个通用字段
                String[] pool = {"单据编号", "单据日期", "金额", "摘要", "付款方", "收款方", "经办机构", "业务类型"};
                int count = 4 + Math.abs(docType.hashCode()) % 3;
                for (int i = 0; i < count; i++) {
                    String key = pool[i];
                    f.put(key, switch (key) {
                        case "单据编号" -> "DJ" + String.format("%012d", Math.abs(rnd.nextLong()) % 1_000_000_000_000L);
                        case "单据日期" -> (2025 + rnd.nextInt(2)) + "-" + String.format("%02d", 1 + rnd.nextInt(12))
                                + "-" + String.format("%02d", 1 + rnd.nextInt(28));
                        case "金额" -> money(rnd);
                        case "摘要" -> "信贷业务" + of(rnd, "放款", "还款", "展期", "结息", "担保变更") + "单据（演示）";
                        case "付款方", "收款方" -> of(rnd, "华信贸易有限公司", "中恒实业有限公司", "恒信商贸有限公司");
                        case "经办机构" -> "B0" + String.format("%04d", rnd.nextInt(10_000));
                        default -> of(rnd, "对公贷款", "个人经营贷", "票据贴现", "保函");
                    });
                }
            }
        }
        return f;
    }

    private String money(Random rnd) {
        return String.format("%d.%02d", 1_000 + rnd.nextInt(9_999_000), rnd.nextInt(100));
    }

    private String of(Random rnd, String... options) {
        return options[rnd.nextInt(options.length)];
    }

    private String alnum(Random rnd, int len) {
        StringBuilder sb = new StringBuilder();
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
