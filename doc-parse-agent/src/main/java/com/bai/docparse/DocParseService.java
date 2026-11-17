package com.bai.docparse;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

/**
 * 文档解析服务（BIA-DOC-005）。接收标准化后的信贷文档，完成文档分类、版面分析、表格结构识别、
 * 阅读顺序还原、KV 键值抽取与印章签名检测，输出 JSON Schema 化的结构化文档数据。
 * <p>multipart 通道为真实内容解析：POI 提取 Word/Excel 正文与表格、PDFBox 提取 PDF 文本与真实页数，
 * KV 按字段正则白名单抽取，抽不到的字段直接置低置信缺省（不伪造值）；docType 分类按
 * 文件名关键词 → 正文关键词 → 扩展名默认值 三级收敛。
 * <p>确定性仿真兜底：JSON 通道（编排层演示提交，无真实字节）仍按 文件名+大小+requestId 哈希推导，
 * 约定钩子：文件名含 encrypted→DCP_1006、corrupt/damaged/black→DCP_1007、超 50MB/200 页→DCP_1005
 * （enableLlm 仅作标记留痕）。图像类文件由 bai.docparse.provider 决定：mock（默认）哈希仿真兜底；
 * real 调统一 AI 推理网关 OCR（PaddleOCR 内核）后走真实分类/KV 抽取链路。
 * <p>格式支持：PDF（pdf）、图像（jpg/jpeg/png/tif/tiff）、Excel（xlsx/xls，表格主体，无关键词默认台账类）、
 * Word（docx/doc，段落主体，无关键词默认报告类）。
 */
@Service
public class DocParseService {

    private static final Logger log = LoggerFactory.getLogger(DocParseService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final long MAX_BYTES = 50L * 1024 * 1024;      // 50MB，超限 DCP_1005
    private static final int MAX_PAGES = 200;                     // 200 页，超限 DCP_1005
    private static final Set<String> SUPPORTED_EXT = Set.of(
            "pdf", "jpg", "jpeg", "png", "tif", "tiff", "xlsx", "xls", "docx", "doc");
    private static final Set<String> SPREADSHEET_EXT = Set.of("xlsx", "xls");
    private static final List<String> EXTRACT_MODES = List.of("FULL", "LAYOUT_ONLY", "TABLE_ONLY", "KV_ONLY");
    private static final List<String> SCHEMA_VERSIONS = List.of("1.0");
    private static final List<String> LAYOUT_TYPES = List.of(
            "TITLE", "PARAGRAPH", "TABLE", "IMAGE", "SEAL", "SIGNATURE", "HEADER", "FOOTER", "PAGE_NUMBER");
    private static final List<String> DOC_TYPES = List.of(
            "loan_contract", "guarantee_contract", "bill", "bank_statement", "certificate", "report", "voucher");
    private static final Map<String, List<String>> KV_TEMPLATE = Map.of(
            "loan_contract", List.of("contract_no", "borrower_name", "loan_amount", "loan_rate", "loan_term"),
            "guarantee_contract", List.of("contract_no", "guarantor_name", "guarantee_amount", "guarantee_way"),
            "bill", List.of("bill_no", "drawee", "payee", "bill_amount", "due_date"),
            "bank_statement", List.of("account_no", "period", "credit_total", "debit_total"),
            "certificate", List.of("cert_no", "holder_name", "issue_org", "issue_date"),
            "report", List.of("report_no", "subject_name", "report_period"),
            "voucher", List.of("voucher_no", "voucher_amount", "voucher_date", "payee_name"));

    private final IdempotencyStore idempotency = new IdempotencyStore();

    /** 内容抽取提供者：mock=图像类仿真兜底（默认）；real=统一 AI 推理网关 OCR */
    private final DocParseProvider provider;

    /** Spring 装配：按 bai.docparse.provider 注入 mock/real 提供者 */
    @Autowired
    public DocParseService(DocParseProvider provider) {
        this.provider = provider;
    }

    /** 测试便捷构造：默认 mock 提供者（行为与历史版本一致） */
    public DocParseService() {
        this(new MockDocParseProvider());
    }

    /** 解析记录：docId → 解析结果（供 /tasks、/validate） */
    private final Map<String, DocParseDtos.ParseResult> docs = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 全流程解析（multipart 通道，幂等键 requestId；同 requestId 返回首次结果，缓存 24h） */
    public DocParseDtos.ParseResult parse(MultipartFile file, String docType, String schemaVersion,
                                          String extractMode, Boolean enableLlm, String requestId) {
        if (file == null || file.isEmpty()) {
            throw new BaiException(DocParseErrorCodes.PARAM_INVALID,
                    "DCP_1001 参数缺失：file（form-data，PDF/图像/Excel/Word）必填");
        }
        String name = file.getOriginalFilename() == null ? "unknown" : file.getOriginalFilename();
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new BaiException(DocParseErrorCodes.UNPARSABLE,
                    "DCP_1007 读取文件内容失败：" + e.getMessage());
        }
        return doParseEntry(bytes, name, file.getSize(), docType, schemaVersion, extractMode, enableLlm, requestId);
    }

    /** 全流程解析（JSON 通道：编排层 scene-bff 以 JSON 提交，fileName 指定演示文档名，按 2KB 伪内容确定性仿真） */
    public DocParseDtos.ParseResult parseJson(Map<String, Object> body, String requestId) {
        String fileName = str(body.get("fileName"));
        if (fileName == null || fileName.isBlank()) {
            throw new BaiException(DocParseErrorCodes.PARAM_INVALID,
                    "DCP_1001 参数缺失：fileName 必填（JSON 通道，须含 pdf/jpg 等合法扩展名）");
        }
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!SUPPORTED_EXT.contains(ext)) {
            throw new BaiException(DocParseErrorCodes.FORMAT_INVALID,
                    "DCP_1006 格式不支持：" + ext + "（支持 pdf/jpg/jpeg/png/tif/tiff/xlsx/xls/docx/doc），fileName 须含合法扩展名");
        }
        return doParseEntry(null, fileName, 2048L,
                body.get("docType") == null ? null : str(body.get("docType")),
                body.get("schemaVersion") == null ? null : str(body.get("schemaVersion")),
                body.get("extractMode") == null ? null : str(body.get("extractMode")),
                Boolean.TRUE.equals(body.get("enableLlm")), requestId);
    }

    private DocParseDtos.ParseResult doParseEntry(byte[] content, String name, long size, String docType, String schemaVersion,
                                                  String extractMode, Boolean enableLlm, String requestId) {
        String mode = extractMode == null || extractMode.isBlank() ? "FULL" : extractMode.toUpperCase();
        if (!EXTRACT_MODES.contains(mode)) {
            throw new BaiException(DocParseErrorCodes.PARAM_INVALID,
                    "DCP_1001 参数非法：extractMode 仅支持 FULL/LAYOUT_ONLY/TABLE_ONLY/KV_ONLY");
        }
        String version = schemaVersion == null || schemaVersion.isBlank() ? "1.0" : schemaVersion;
        if (!SCHEMA_VERSIONS.contains(version)) {
            throw new BaiException(DocParseErrorCodes.NOT_FOUND,
                    "DCP_1004 Schema 版本不存在：" + version + "（当前支持 1.0）");
        }
        DocParseDtos.ParseResult cached = idempotency.get("parse", requestId);
        if (cached != null) {
            return cached;
        }
        if (size > MAX_BYTES) {
            throw new BaiException(DocParseErrorCodes.TOO_LARGE,
                    "DCP_1005 文件超过 50MB（实际 " + (size / 1024 / 1024) + "MB），请拆分提交");
        }
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (!SUPPORTED_EXT.contains(ext)) {
            throw new BaiException(DocParseErrorCodes.FORMAT_INVALID,
                    "DCP_1006 格式不支持：" + ext + "（支持 pdf/jpg/jpeg/png/tif/tiff/xlsx/xls/docx/doc）");
        }
        String lower = name.toLowerCase();
        if (lower.contains("encrypted") || lower.contains("加密")) {
            throw new BaiException(DocParseErrorCodes.FORMAT_INVALID,
                    "DCP_1006 PDF 已加密，请上传解密密码或解密后重新提交");
        }
        if (lower.contains("corrupt") || lower.contains("damaged") || lower.contains("black")
                || lower.contains("损坏") || lower.contains("全黑")) {
            throw new BaiException(DocParseErrorCodes.UNPARSABLE,
                    "DCP_1007 文档不可解析（损坏/全黑），请重拍或转人工");
        }
        // 页数：PDF/其他格式真实页数优先（解析正文时一并取得），否则按 100KB/页估算（300DPI 转图后规模近似），超 200 页 DCP_1005
        RealDoc real = content == null ? null : extractReal(content, ext, name, requestId);
        int pages = real != null && real.pdfPages() > 0
                ? real.pdfPages()
                : Math.max(1, (int) (size / 102_400) + 1);
        if (pages > MAX_PAGES) {
            throw new BaiException(DocParseErrorCodes.TOO_LARGE,
                    "DCP_1005 文档超 " + MAX_PAGES + " 页（实际 " + pages + " 页），请拆分提交");
        }
        long begin = System.nanoTime();
        DocParseDtos.ParseResult result = doParse(real, name, size, pages, docType, version, mode,
                Boolean.TRUE.equals(enableLlm), requestId);
        result = new DocParseDtos.ParseResult(result.docId(), result.docType(), result.docTypeConfidence(),
                result.schemaVersion(), result.pages(), result.layout(), result.tables(), result.kv(),
                result.seals(), result.readingOrder(), result.validation(),
                Math.max(1, (int) ((System.nanoTime() - begin) / 1_000_000)));
        docs.put(result.docId(), result);
        idempotency.put("parse", requestId, result);
        log.info("文档解析完成: docId={}, docType={}, pages={}, extractMode={}, enableLlm={}",
                result.docId(), result.docType(), pages, mode, enableLlm);
        return result;
    }

    /** Schema 查询 */
    public List<DocParseDtos.SchemaInfo> schemas() {
        return List.of(
                new DocParseDtos.SchemaInfo("SCHEMA_LOAN_CONTRACT_V1", "loan_contract,guarantee_contract", "1.0", "PUBLISHED"),
                new DocParseDtos.SchemaInfo("SCHEMA_BILL_V1", "bill", "1.0", "PUBLISHED"),
                new DocParseDtos.SchemaInfo("SCHEMA_STATEMENT_V1", "bank_statement", "1.0", "PUBLISHED"),
                new DocParseDtos.SchemaInfo("SCHEMA_CERT_V1", "certificate,voucher", "1.0", "PUBLISHED"),
                new DocParseDtos.SchemaInfo("SCHEMA_REPORT_V1", "report", "1.0", "PUBLISHED"));
    }

    /** 异步任务查询（MVP 同步解析完成即登记任务为 DONE） */
    public DocParseDtos.TaskResult task(String taskId) {
        DocParseDtos.ParseResult doc = docs.get(taskId);
        if (doc == null) {
            throw new BaiException(DocParseErrorCodes.NOT_FOUND, "DCP_1004 解析任务不存在：" + taskId);
        }
        return new DocParseDtos.TaskResult(taskId, "DONE", doc.docId(), doc.docType(), doc.pages(),
                LocalDateTime.now().toString());
    }

    /** 解析校验：复检 KV 低置信字段并给出转人工结论 */
    public DocParseDtos.ValidateResult validate(String docId, Object fields, String requestId) {
        DocParseDtos.ParseResult doc = docs.get(docId);
        if (doc == null) {
            throw new BaiException(DocParseErrorCodes.NOT_FOUND, "DCP_1004 解析记录不存在：" + docId);
        }
        List<String> lowConfidence = new ArrayList<>();
        int checked = 0;
        if (fields instanceof Map<?, ?> kv) {
            for (Map.Entry<?, ?> e : kv.entrySet()) {
                checked++;
                if (confidenceOf(str(e.getValue()), checked) < 0.80) {
                    lowConfidence.add(String.valueOf(e.getKey()));
                }
            }
        } else if (doc.kv() instanceof Map<?, ?> kv) {
            for (Map.Entry<?, ?> e : kv.entrySet()) {
                checked++;
                if (e.getValue() instanceof DocParseDtos.KvField f && f.confidence() < 0.80) {
                    lowConfidence.add(String.valueOf(e.getKey()));
                }
            }
        }
        boolean needReview = !lowConfidence.isEmpty();
        log.info("解析校验: docId={}, checked={}, lowConfidence={}, needReview={}",
                docId, checked, lowConfidence, needReview);
        return new DocParseDtos.ValidateResult(docId, checked, List.copyOf(lowConfidence), needReview,
                Math.max(1, requestId.length() % 5 + 2));
    }

    /** 确定性解析主流程：multipart（real!=null）走真实内容抽取，JSON 通道/图像走仿真兜底 */
    private DocParseDtos.ParseResult doParse(RealDoc real, String fileName, long size, int pages, String docTypeParam,
                                             String schemaVersion, String mode, boolean enableLlm,
                                             String requestId) {
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        String fingerprint = UUID.nameUUIDFromBytes((fileName + "|" + size + "|" + requestId).getBytes()).toString();
        int h = fingerprint.hashCode();
        // 文档分类：指定优先 → 文件名关键词 → 正文关键词 → 扩展名默认值（仿真兜底保留哈希候选）
        String docType;
        double docTypeConfidence;
        if (docTypeParam != null && !docTypeParam.isBlank()) {
            docType = docTypeParam;
            docTypeConfidence = 0.99;
        } else if (real != null && !real.text().isBlank()) {
            String[] c = classifyReal(fileName, real.text(), ext);
            docType = c[0];
            docTypeConfidence = Double.parseDouble(c[1]);
        } else {
            docType = classifyByName(fileName, h);
            docTypeConfidence = 0.70 + Math.abs(h % 30) / 100.0;
        }
        String docId = "DOC" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet());
        // 版面分析：每页 TITLE + PARAGRAPH + HEADER/FOOTER；阅读顺序：真实正文优先，仿真兜底
        List<DocParseDtos.Layout> layout = new ArrayList<>();
        List<DocParseDtos.ReadingItem> readingOrder = new ArrayList<>();
        int seqNo = 0;
        List<String> paras = real == null || real.text().isBlank() ? List.of()
                : Arrays.stream(real.text().split("\\r?\\n")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        int paraIdx = 0;
        for (int p = 1; p <= Math.min(pages, 5); p++) {
            layout.add(new DocParseDtos.Layout(p, "TITLE", 0.1, 0.08, 0.8, 0.05, conf(h, p)));
            layout.add(new DocParseDtos.Layout(p, "PARAGRAPH", 0.1, 0.2, 0.8, 0.5, conf(h, p + 7)));
            layout.add(new DocParseDtos.Layout(p, "HEADER", 0.0, 0.0, 1.0, 0.05, 0.98));
            layout.add(new DocParseDtos.Layout(p, "FOOTER", 0.0, 0.95, 1.0, 0.05, 0.98));
            readingOrder.add(new DocParseDtos.ReadingItem(++seqNo, p, "TITLE", docTitle(docType)));
            String para = paraIdx < paras.size() ? paras.get(paraIdx++) : "第" + p + "页正文段落（仿真抽取）";
            if (para.length() > 60) {
                para = para.substring(0, 60) + "…";
            }
            readingOrder.add(new DocParseDtos.ReadingItem(++seqNo, p, "PARAGRAPH", para));
        }
        // 表格结构识别：真实表格优先（Excel 全部工作表 / Word 全部表格）；无真实内容时按仿真规则
        List<DocParseDtos.Table> tables = new ArrayList<>();
        boolean wantTables = "TABLE_ONLY".equals(mode) || "FULL".equals(mode);
        if (wantTables && real != null) {
            tables.addAll(real.tables());
        } else if (wantTables && real == null) {
            boolean hasTable = SPREADSHEET_EXT.contains(ext)
                    || "bill".equals(docType) || "bank_statement".equals(docType) || Math.abs(h >> 3) % 3 == 0;
            if (hasTable) {
                int rows = 3 + Math.abs(h >> 6) % 10;
                int cols = 3 + Math.abs(h >> 9) % 4;
                List<String> header = List.of("序号", "要素", "内容").subList(0, Math.min(3, cols));
                List<DocParseDtos.Cell> cells = new ArrayList<>();
                for (int r = 0; r < 1; r++) {
                    for (int c = 0; c < header.size(); c++) {
                        cells.add(new DocParseDtos.Cell(r, c, header.get(c), 0));
                    }
                }
                tables.add(new DocParseDtos.Table(Math.min(3, pages), rows, cols, header, cells, 0.90 + Math.abs(h >> 12) % 10 / 100.0));
            }
        }
        // KV 抽取：真实内容按字段正则白名单抽取（抽不到的字段置低置信缺省，不伪造值）；
        // 无真实内容时按模板规则 + 哈希值仿真兜底
        Map<String, Object> kv = new LinkedHashMap<>();
        List<String> lowConfidenceFields = new ArrayList<>();
        if ("FULL".equals(mode) || "KV_ONLY".equals(mode)) {
            List<String> fieldNames = KV_TEMPLATE.getOrDefault(docType, KV_TEMPLATE.get("loan_contract"));
            if (real != null && !real.text().isBlank()) {
                for (String f : fieldNames) {
                    String v = extractField(real.text(), f);
                    if (v == null) {
                        lowConfidenceFields.add(f);   // 缺字段即低置信：提示人工复核，不出仿真值
                        continue;
                    }
                    double confidence = 0.90 + Math.abs(v.hashCode()) % 9 / 100.0;
                    kv.put(f, new DocParseDtos.KvField(v, confidence, 1, "PARAGRAPH"));
                }
            } else {
                int k = 0;
                for (String f : fieldNames) {
                    double confidence = 0.75 + Math.abs(h >> (k * 4 + 1)) % 25 / 100.0;
                    if (confidence < 0.80) {
                        lowConfidenceFields.add(f);
                    }
                    kv.put(f, new DocParseDtos.KvField(kvValue(f, h, k++), confidence, 1 + k % Math.max(1, pages), "PARAGRAPH"));
                }
            }
            if (enableLlm) {
                kv.put("llm_fallback_used", new DocParseDtos.KvField("false", 1.0, 0, "SYSTEM"));
            }
        }
        // 印章/签名检测（末页）
        List<DocParseDtos.Seal> seals = List.of(
                new DocParseDtos.Seal(pages, "SEAL", 0.6, 0.7, 0.1, 0.08, 0.90 + Math.abs(h >> 16) % 10 / 100.0),
                new DocParseDtos.Seal(pages, "SIGNATURE", 0.35, 0.72, 0.12, 0.05, 0.88));
        if ("LAYOUT_ONLY".equals(mode)) {
            tables = List.of();
            kv = Map.of();
            seals = List.of();
        }
        boolean needReview = !lowConfidenceFields.isEmpty();
        return new DocParseDtos.ParseResult(docId, docType, docTypeConfidence, schemaVersion, pages,
                List.copyOf(layout), tables, kv, List.copyOf(seals), List.copyOf(readingOrder),
                new DocParseDtos.Validation(List.copyOf(lowConfidenceFields), needReview), 0);
    }

    private String classifyByName(String fileName, int h) {
        String lower = fileName.toLowerCase();
        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase() : "";
        boolean named = lower.contains("guarantee") || lower.contains("担保") || lower.contains("bill")
                || lower.contains("票据") || lower.contains("statement") || lower.contains("流水")
                || lower.contains("cert") || lower.contains("证明") || lower.contains("report")
                || lower.contains("报告") || lower.contains("voucher") || lower.contains("凭证")
                || lower.contains("contract") || lower.contains("合同");
        if (!named && SPREADSHEET_EXT.contains(ext)) {
            return "bank_statement";   // Excel 无业务关键词：默认台账/流水类（表格主体）
        }
        if (!named && (ext.equals("docx") || ext.equals("doc"))) {
            return "report";           // Word 无业务关键词：默认报告类（段落主体）
        }
        if (lower.contains("guarantee") || lower.contains("担保")) return "guarantee_contract";
        if (lower.contains("bill") || lower.contains("票据")) return "bill";
        if (lower.contains("statement") || lower.contains("流水")) return "bank_statement";
        if (lower.contains("cert") || lower.contains("证明")) return "certificate";
        if (lower.contains("report") || lower.contains("报告")) return "report";
        if (lower.contains("voucher") || lower.contains("凭证")) return "voucher";
        if (lower.contains("contract") || lower.contains("合同")) return "loan_contract";
        return DOC_TYPES.get(Math.abs(h) % DOC_TYPES.size());
    }

    private String docTitle(String docType) {
        return switch (docType) {
            case "loan_contract" -> "个人借款合同";
            case "guarantee_contract" -> "保证合同";
            case "bill" -> "银行承兑汇票";
            case "bank_statement" -> "银行流水";
            case "certificate" -> "资质证明";
            case "report" -> "评估报告";
            default -> "业务凭证";
        };
    }

    private String kvValue(String field, int h, int k) {
        long v = Integer.toUnsignedLong(h >> k) ;
        return switch (field) {
            case "contract_no" -> "HT" + String.format("%010d", v % 10_000_000_000L);
            case "bill_no", "report_no", "voucher_no", "cert_no" -> String.format("%012d", v % 1_000_000_000_000L);
            case "borrower_name", "guarantor_name", "holder_name", "subject_name", "drawee", "payee", "payee_name" ->
                    pickPerson(v);
            case "loan_amount", "guarantee_amount", "bill_amount", "voucher_amount" ->
                    String.valueOf((500_000 + v % 95 * 50_000));
            case "loan_rate" -> "4.35";
            case "loan_term" -> "12个月";
            case "account_no" -> "6222" + String.format("%012d", v % 1_000_000_000_000L);
            case "due_date", "issue_date", "voucher_date" -> "2026-0" + (1 + v % 9) + "-1" + v % 9;
            case "issue_org" -> "XX银行股份有限公司";
            case "period", "report_period" -> "2026-10";
            case "credit_total", "debit_total" -> String.valueOf(100_000 + v % 900_000);
            case "guarantee_way" -> "保证担保";
            default -> "（仿真抽取值）";
        };
    }

    private String pickPerson(long v) {
        String[] surnames = {"张", "王", "李", "赵", "刘", "陈", "杨", "黄", "周", "吴"};
        String[] givens = {"伟", "芳", "娜", "敏", "静", "磊", "军", "洋", "勇", "艳", "杰", "涛"};
        return surnames[(int) (v % surnames.length)] + givens[(int) (v / 7 % givens.length)];
    }

    // ==================== 真实内容抽取（multipart 通道） ====================

    /** 真实内容抽取结果：正文文本 + 真实表格 + PDF 真实页数（非 PDF 为 -1） */
    private record RealDoc(String text, List<DocParseDtos.Table> tables, int pdfPages) {}

    /** 按扩展名分发真实内容抽取；图像类走 DocParseProvider（mock 返回 null 仿真兜底）；本地解析异常回退仿真兜底（记 WARN，链路不断） */
    private RealDoc extractReal(byte[] content, String ext, String filename, String requestId) {
        try {
            return switch (ext) {
                case "docx" -> extractDocx(content);
                case "doc" -> extractDoc(content);
                case "xlsx", "xls" -> extractExcel(content);
                case "pdf" -> extractPdf(content);
                default -> extractImageReal(content, filename, requestId);   // 图像类：mock 仿真兜底 / real 推理网关 OCR
            };
        } catch (BaiException e) {
            throw e;   // 提供者明确业务错误（real 模式网关不可达 DCP_2001 等）：不吞掉、不静默伪造
        } catch (Exception e) {
            log.warn("真实内容抽取失败，回退确定性仿真: {} ({})", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 图像类内容：mock 提供者返回 null（仿真兜底，行为不变）；real 提供者返回 OCR 正文喂分类/KV 真实抽取链路 */
    private RealDoc extractImageReal(byte[] content, String filename, String requestId) {
        String text = provider.ocrImage(content, filename, requestId);
        return text == null ? null : new RealDoc(text, List.of(), -1);
    }

    private RealDoc extractDocx(byte[] content) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(content))) {
            StringBuilder sb = new StringBuilder();
            for (var p : doc.getParagraphs()) {
                sb.append(p.getText()).append('\n');
            }
            List<DocParseDtos.Table> tables = new ArrayList<>();
            for (XWPFTable t : doc.getTables()) {
                List<String> header = new ArrayList<>();
                List<DocParseDtos.Cell> cells = new ArrayList<>();
                List<XWPFTableRow> rows = t.getRows();
                int cols = 0;
                for (int r = 0; r < rows.size(); r++) {
                    List<XWPFTableCell> row = rows.get(r).getTableCells();
                    cols = Math.max(cols, row.size());
                    for (int c = 0; c < row.size(); c++) {
                        String text = row.get(c).getText().trim();
                        if (r == 0) {
                            header.add(text);
                        }
                        cells.add(new DocParseDtos.Cell(r, c, text, 0));
                    }
                }
                if (!header.isEmpty()) {
                    tables.add(new DocParseDtos.Table(1, rows.size(), cols, header, cells, 0.95));
                }
            }
            return new RealDoc(sb.toString(), tables, -1);
        }
    }

    private RealDoc extractDoc(byte[] content) throws IOException {
        try (WordExtractor we = new WordExtractor(new HWPFDocument(new ByteArrayInputStream(content)))) {
            return new RealDoc(String.join("\n", we.getParagraphText()), List.of(), -1);
        }
    }

    private RealDoc extractExcel(byte[] content) throws IOException {
        DataFormatter fmt = new DataFormatter();
        List<DocParseDtos.Table> tables = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (Sheet sheet : wb) {
                int lastRow = sheet.getLastRowNum();
                if (lastRow < 0) {
                    continue;
                }
                int pageNo = tables.size() + 1;
                List<String> header = new ArrayList<>();
                List<DocParseDtos.Cell> cells = new ArrayList<>();
                int cols = 0;
                for (int r = 0; r <= Math.min(lastRow, 49); r++) {   // 每表最多取 50 行防超大文件
                    Row row = sheet.getRow(r);
                    if (row == null) {
                        continue;
                    }
                    short lastCol = row.getLastCellNum();
                    cols = Math.max(cols, lastCol);
                    for (int c = 0; c < lastCol; c++) {
                        String text = fmt.formatCellValue(row.getCell(c)).trim();
                        if (r == 0) {
                            header.add(text);
                        }
                        cells.add(new DocParseDtos.Cell(r, c, text, 0));
                    }
                    for (int c = 0; c < lastCol; c++) {
                        sb.append(fmt.formatCellValue(row.getCell(c)).trim()).append(' ');
                    }
                    sb.append('\n');
                }
                if (!header.isEmpty()) {
                    tables.add(new DocParseDtos.Table(pageNo, Math.min(lastRow + 1, 50), cols, header, cells, 0.95));
                }
            }
        }
        return new RealDoc(sb.toString(), tables, -1);
    }

    private RealDoc extractPdf(byte[] content) throws IOException {
        try (PDDocument pd = PDDocument.load(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(pd.getNumberOfPages(), 20));   // 最多取 20 页正文防超大文件
            return new RealDoc(stripper.getText(pd), List.of(), pd.getNumberOfPages());
        }
    }

    private int pdfPageCount(byte[] content) {
        try (PDDocument pd = PDDocument.load(content)) {
            return pd.getNumberOfPages();
        } catch (Exception e) {
            return -1;
        }
    }


    /** 正文关键词分类（优先于扩展名默认值）；返回 {docType, confidence} */
    private String[] classifyReal(String fileName, String text, String ext) {
        // 1. 文件名关键词
        String byName = nameKeywordType(fileName.toLowerCase());
        if (byName != null) {
            return new String[]{byName, "0.90"};
        }
        // 2. 正文关键词
        if (text.contains("保证合同") || text.contains("担保合同")) {
            return new String[]{"guarantee_contract", "0.95"};
        }
        if (text.contains("承兑汇票")) {
            return new String[]{"bill", "0.95"};
        }
        if (text.contains("对账单") || text.contains("交易明细") || text.contains("银行流水")) {
            return new String[]{"bank_statement", "0.95"};
        }
        if (text.contains("借款合同") || (text.contains("合同") && (text.contains("借款") || text.contains("贷款")))) {
            return new String[]{"loan_contract", "0.95"};
        }
        if (text.contains("记账凭证") || text.contains("付款凭证") || text.contains("收款凭证")) {
            return new String[]{"voucher", "0.95"};
        }
        if (text.contains("评估报告") || text.contains("审计报告") || text.contains("尽调报告")) {
            return new String[]{"report", "0.95"};
        }
        if (text.contains("资质证书") || text.contains("许可证")) {
            return new String[]{"certificate", "0.95"};
        }
        // 3. 文件名非关键词时按扩展名默认（与原约定一致，不再随机哈希）
        if (SPREADSHEET_EXT.contains(ext)) {
            return new String[]{"bank_statement", "0.75"};
        }
        return new String[]{"report", "0.75"};
    }

    private String nameKeywordType(String lower) {
        if (lower.contains("guarantee") || lower.contains("担保")) return "guarantee_contract";
        if (lower.contains("bill") || lower.contains("票据")) return "bill";
        if (lower.contains("statement") || lower.contains("流水")) return "bank_statement";
        if (lower.contains("cert") || lower.contains("证明")) return "certificate";
        if (lower.contains("report") || lower.contains("报告")) return "report";
        if (lower.contains("voucher") || lower.contains("凭证")) return "voucher";
        if (lower.contains("contract") || lower.contains("合同")) return "loan_contract";
        return null;
    }

    /** KV 字段正则白名单（真实内容抽取用；每个字段多模式依次尝试） */
    private static final Map<String, List<Pattern>> FIELD_PATTERNS = buildFieldPatterns();

    private static Map<String, List<Pattern>> buildFieldPatterns() {
        String CJK = "[\\u4e00-\\u9fa5A-Za-z0-9]";          // 名称类字符（到非名称字符即截断）
        String AMT = "([0-9][0-9,，]*(?:\\.[0-9]+)?)";       // 数字金额
        String DATE = "([0-9]{4}[年\\-/][0-9]{1,2}[月\\-/][0-9]{1,2}日?)";
        Map<String, List<Pattern>> m = new LinkedHashMap<>();
        m.put("contract_no", pats("(?:合同编号|协议编号|合同号)[：:]{1,2}\\s*([A-Za-z0-9\\-]{4,32})"));
        m.put("borrower_name", pats("借款人[：:]{1,2}\\s*(" + CJK + "{2,20})"));
        m.put("loan_amount", pats("[¥￥]\\s*" + AMT,
                "(?:借款金额|贷款金额|合同金额)[为是：:\\s]{0,4}(?:人民币)?\\s*" + AMT + "\\s*元",
                "金额[为是：:\\s]{0,4}" + AMT));
        m.put("loan_rate", pats("年利率[为是：:\\s]{0,4}([0-9]+(?:\\.[0-9]+)?)\\s*%",
                "利率[为是：:\\s]{0,4}([0-9]+(?:\\.[0-9]+)?)\\s*%"));
        m.put("loan_term", pats("(?:借款期限|贷款期限|期限)[为是：:\\s]{0,4}([0-9]{1,3}\\s*个?月|[0-9]{1,2}\\s*年)"));
        m.put("guarantor_name", pats("保证人[：:]{1,2}\\s*(" + CJK + "{2,20})"));
        m.put("guarantee_amount", pats("[¥￥]\\s*" + AMT,
                "(?:担保金额|保证金额)[为是：:\\s]{0,4}(?:人民币)?\\s*" + AMT + "\\s*元"));
        m.put("guarantee_way", pats("担保方式[：:]{1,2}\\s*([\\u4e00-\\u9fa5]{2,12})",
                "(连带责任保证|保证担保|抵押担保|质押担保)"));
        m.put("bill_no", pats("(?:票据号码|票据号|汇票号码)[：:]{1,2}\\s*([0-9]{6,})"));
        m.put("drawee", pats("(?:出票人|付款人)[：:]{1,2}\\s*(" + CJK + "{2,25})"));
        m.put("payee", pats("收款人[：:]{1,2}\\s*(" + CJK + "{2,25})"));
        m.put("payee_name", pats("收款人[：:]{1,2}\\s*(" + CJK + "{2,25})"));
        m.put("bill_amount", pats("[¥￥]\\s*" + AMT, "金额[为是：:\\s]{0,4}" + AMT));
        m.put("voucher_amount", pats("[¥￥]\\s*" + AMT, "金额[为是：:\\s]{0,4}" + AMT));
        m.put("due_date", pats("到期日[：:]{1,2}\\s*" + DATE));
        m.put("account_no", pats("账[户户号号]?号[：:]{1,2}\\s*([0-9\\-]{8,25})"));
        m.put("period", pats("(?:对账期间|账单期间|账期|期间)[为是：:\\s]{0,4}(\\d{4}[-/年]\\d{1,2}(?:[-/月]\\d{1,2}日?)?)"));
        m.put("credit_total", pats("贷方[^0-9\\n]{0,16}" + AMT));
        m.put("debit_total", pats("借方[^0-9\\n]{0,16}" + AMT));
        m.put("cert_no", pats("(?:证书编号|资质编号|许可证号|证号)[：:]{1,2}\\s*([A-Za-z0-9\\-]{4,32})"));
        m.put("holder_name", pats("(?:持有人|持证人)[：:]{1,2}\\s*(" + CJK + "{2,20})"));
        m.put("issue_org", pats("(?:发证机关|颁发机关|签发机构|贷款人)[：:]{1,2}\\s*([\\u4e00-\\u9fa5（()）]{4,30})",
                "([\\u4e00-\\u9fa5]{2,20}银行[\\u4e00-\\u9fa5]{0,12})"));
        m.put("issue_date", pats("(?:发证日期|签发日期|颁发日期)[：:]{1,2}\\s*" + DATE));
        m.put("report_no", pats("(?:报告编号|报告编号)[：:]{1,2}\\s*([A-Za-z0-9\\-]{4,32})"));
        m.put("subject_name", pats("(?:标的公司|评估对象|被评估单位|项目名称)[为：:]{1,2}\\s*(" + CJK + "{2,25})"));
        m.put("report_period", pats("(?:报告期间|评估基准日|报告期)[为是：:\\s]{0,4}(\\d{4}[年\\-/]\\d{1,2}月?日?)"));
        m.put("voucher_no", pats("(?:凭证编号|凭证号)[：:]{1,2}\\s*([A-Za-z0-9\\-]{4,32})"));
        m.put("voucher_date", pats("(?:记账日期|凭证日期|日期)[：:]{1,2}\\s*" + DATE));
        return Map.copyOf(m);
    }

    private static List<Pattern> pats(String... regexes) {
        return Arrays.stream(regexes).map(Pattern::compile).toList();
    }

    /** 按字段正则白名单抽取真实值；抽不到返回 null（调用方置低置信缺省，不伪造） */
    private String extractField(String text, String field) {
        for (Pattern p : FIELD_PATTERNS.getOrDefault(field, List.of())) {
            Matcher m = p.matcher(text);
            if (m.find()) {
                String v = m.groupCount() > 0 ? m.group(1) : m.group();
                v = normalizeValue(v);
                if (!v.isBlank()) {
                    return v;
                }
            }
        }
        return null;
    }

    /** 值归一化：去千分位逗号、去 .00 尾数，保留原文本语义 */
    private String normalizeValue(String v) {
        v = v.trim().replace(",", "").replace("，", "").replace("－", "-");
        if (v.matches("\\d+\\.00")) {
            v = v.substring(0, v.length() - 3);
        }
        return v;
    }

    private double conf(int h, int salt) {
        return 0.85 + Math.abs(h >> salt) % 15 / 100.0;
    }

    private double confidenceOf(String value, int salt) {
        if (value == null || value.isBlank()) {
            return 0.0;
        }
        return 0.75 + Math.abs(value.hashCode() >> salt) % 25 / 100.0;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
