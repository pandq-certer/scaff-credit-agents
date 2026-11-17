package com.bai.onsiteinspect;

import com.bai.common.BaiException;
import com.bai.common.IdempotencyStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 现场尽调服务（BIA-EPT-064）。以下户任务与查勘清单为输入，完成下户任务管理（提纲生成/预约安排/双人成行）、
 * 查勘清单/经营五看/抵押物点位引导、现场证据采集（时间位置一致性校验）、三层访谈（双录授权）与现场结论/素材生成。
 * <p>状态机：CREATE→PENDING(待预约) →UPDATE(预约)→BOOKED(已预约) →START→IN_PROGRESS(进行中)
 * →COMPLETE→COMPLETED(已完成)；ABORT 任一非终态→ABORTED(异常终止)；状态只进不退（设计 9.1）。
 * <p>幂等：POST 以 requestId 幂等，证据采集以 taskId+mediaRef 幂等（设计 4.8）；同幂等键参数不同→OIS_4004。
 * <p>限流：默认 200 QPS、证据采集 500 QPS（Controller 层令牌桶）。
 */
@Service
public class OnsiteInspectService {

    private static final Logger log = LoggerFactory.getLogger(OnsiteInspectService.class);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ACTIONS = Set.of("CREATE", "UPDATE", "START", "COMPLETE", "ABORT");
    private static final Set<String> INSPECT_TYPES = Set.of("PRE_LOAN", "POST_LOAN", "COLLATERAL", "MOBILE_OUTREACH");
    private static final Set<String> EVIDENCE_TYPES = Set.of("PHOTO", "VIDEO", "AUDIO", "DOCUMENT", "AUTHORIZATION");
    private static final Set<String> LAYERS = Set.of("BOSS", "FINANCE", "LINE");
    private static final Set<String> FIVE_VIEW_KEYS = Set.of("生产", "库存", "人", "单据", "环境");
    private static final Set<String> VIEW_LEVELS = Set.of("NORMAL", "ATTENTION", "ABNORMAL");
    private static final Set<String> ABNORMAL_TYPES = Set.of("AB01", "AB02", "AB03", "AB04", "AB05", "AB06");
    private static final Set<String> ABNORMAL_LEVELS = Set.of("提示", "警告", "严重");
    private static final Set<String> TERMINAL = Set.of("COMPLETED", "ABORTED");
    private static final Set<String> CHECKLIST_VERSIONS = Set.of("V1.0", "V2.0");
    private static final Set<String> COLLATERAL_TYPES = Set.of("不动产", "动产", "设备", "车辆");
    /** 证据一致性规则（设计 7.1）：拍摄时间在任务时间 ±2 小时，位置在预约地点 5km 内 */
    private static final long EVIDENCE_WINDOW_HOURS = 2;
    private static final double EVIDENCE_RADIUS_KM = 5.0;

    /** 幂等缓存：requestId / taskId+mediaRef，7 天 */
    private final IdempotencyStore idempotency = new IdempotencyStore(7 * 24 * 3600_1000L);
    /** 幂等指纹：同幂等键参数不同 → OIS_4004 幂等冲突 */
    private final Map<String, String> idemFingerprints = new ConcurrentHashMap<>();

    /** 仿真存储：任务/证据/访谈/结论（生产落 PostgreSQL/媒资平台，见设计 5.3） */
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private final Map<String, Evidence> evidences = new ConcurrentHashMap<>();
    private final Map<String, Interview> interviews = new ConcurrentHashMap<>();
    private final Map<String, Summary> summaries = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();

    /** 内部下户任务模型（状态流转以整条替换实现） */
    private record Task(String taskId, String customerId, String loanNo, String inspectType,
                        List<String> inspectors, String appointmentTime, String appointmentLocation,
                        String appointmentContact, String checklistVersion, String status,
                        OnsiteInspectDtos.Outline outline, String createdAt) {}

    /** 内部现场证据索引（oi_evidence） */
    private record Evidence(String evidenceId, String taskId, String evidenceType, String mediaRef,
                            String gps, String captureTime, String quality, boolean indexed, boolean consistency) {}

    /** 内部访谈记录（oi_interview） */
    private record Interview(String interviewId, String taskId, String layer, int questionsCount,
                             String recordingRef, String authorizationRef, String transcriptRef) {}

    /** 内部现场结论（oi_conclusion） */
    private record Summary(String summaryId, String taskId, String conclusion, String materialRef,
                           List<OnsiteInspectDtos.Abnormal> abnormals, String templateVersion) {}

    // -------------------------------------------------------------- 下户任务管理

    /** 下户任务创建/更新/状态流转（action=CREATE/UPDATE/START/COMPLETE/ABORT，幂等 requestId） */
    public OnsiteInspectDtos.TaskView task(Map<String, Object> body, String requestId) {
        String action = str(body == null ? null : body.get("action"));
        if (action == null || action.isBlank()) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：action 必填");
        }
        action = action.toUpperCase();
        return switch (action) {
            case "CREATE" -> createTask(body, requestId);
            case "UPDATE" -> updateTask(body, requestId);
            case "START" -> startTask(body, requestId);
            case "COMPLETE" -> completeTask(body, requestId);
            case "ABORT" -> abortTask(body, requestId);
            default -> throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：action 仅支持 CREATE/UPDATE/START/COMPLETE/ABORT");
        };
    }

    /** 任务创建：双人成行校验（≥2 名无回避冲突调查人）、提纲生成（按尽调类型）、预约即 BOOKED */
    private OnsiteInspectDtos.TaskView createTask(Map<String, Object> body, String requestId) {
        if (isBlank(requestId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：requestId 必填");
        }
        String fingerprint = canonical(body);
        OnsiteInspectDtos.TaskView cached = idemPrologue("task", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        String customerId = str(body.get("customerId"));
        if (isBlank(customerId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：customerId 必填");
        }
        if (!customerId.matches("C\\d{3,}")) {
            throw new BaiException(OnsiteInspectErrorCodes.CUSTOMER_NOT_FOUND,
                    "OIS_4003 客户不存在：" + customerId + "（仿真客户注册表校验，须为 C+数字）");
        }
        String inspectType = str(body.get("inspectType"));
        if (isBlank(inspectType) || !INSPECT_TYPES.contains(inspectType.toUpperCase())) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：inspectType 仅支持 PRE_LOAN/POST_LOAN/COLLATERAL/MOBILE_OUTREACH");
        }
        inspectType = inspectType.toUpperCase();
        String loanNo = str(body.get("loanNo"));
        if (("PRE_LOAN".equals(inspectType) || "POST_LOAN".equals(inspectType)) && isBlank(loanNo)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数缺失：loanNo 必填（贷前/贷后关联贷款编号）");
        }
        if (!isBlank(loanNo) && !loanNo.matches("L\\d{3,}")) {
            throw new BaiException(OnsiteInspectErrorCodes.CUSTOMER_NOT_FOUND,
                    "OIS_4003 贷款不存在：" + loanNo + "（仿真台账校验，须为 L+数字）");
        }
        List<String> inspectors = inspectorsOf(body.get("inspectors"));
        if (inspectors.size() < 2) {
            throw new BaiException(OnsiteInspectErrorCodes.DUAL_INSPECTORS_MISSING,
                    "OIS_4101 双人未成行：调查人不足 2 人（须两名无回避冲突调查人）");
        }
        String version = str(body.get("checklistVersion"));
        if (isBlank(version)) {
            version = "V1.0";
        }
        if (!CHECKLIST_VERSIONS.contains(version)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：checklistVersion 不存在（当前生效 V1.0/V2.0）");
        }
        Map<String, Object> appointment = mapOf(body.get("appointment"));
        LocalDateTime aptTime = parseTime(str(appointment.get("time")), "appointment.time");
        String aptLoc = str(appointment.get("location"));
        String aptContact = str(appointment.get("contact"));
        String status = (aptTime != null || !isBlank(aptLoc)) ? "BOOKED" : "PENDING";
        String taskId = "OIT" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        OnsiteInspectDtos.Outline outline = outlineOf(inspectType, version);
        Task task = new Task(taskId, customerId, loanNo, inspectType, inspectors, aptTime == null ? null : aptTime.toString(),
                aptLoc, aptContact, version, status, outline, LocalDateTime.now().withNano(0).toString());
        tasks.put(taskId, task);
        idemRecord("task", requestId, fingerprint, view(task));
        log.info("下户任务创建: taskId={}, type={}, customer={}, loanNo={}, inspectors={}, status={}, requestId={}",
                taskId, inspectType, customerId, loanNo, inspectors, status, requestId);
        return view(task);
    }

    /** 任务更新：预约安排（→BOOKED，只进不退）/清单版本/双人复核 */
    private OnsiteInspectDtos.TaskView updateTask(Map<String, Object> body, String requestId) {
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填（更新时）");
        }
        Task task = requireTask(taskId);
        String fingerprint = canonical(body);
        OnsiteInspectDtos.TaskView cached = idemPrologue("task", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        List<String> inspectors = task.inspectors();
        if (body.get("inspectors") != null) {
            inspectors = inspectorsOf(body.get("inspectors"));
            if (inspectors.size() < 2) {
                throw new BaiException(OnsiteInspectErrorCodes.DUAL_INSPECTORS_MISSING,
                        "OIS_4101 双人未成行：调查人不足 2 人");
            }
        }
        String version = task.checklistVersion();
        String rawVersion = str(body.get("checklistVersion"));
        if (!isBlank(rawVersion)) {
            if (!CHECKLIST_VERSIONS.contains(rawVersion)) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：checklistVersion 不存在（当前生效 V1.0/V2.0）");
            }
            version = rawVersion;
        }
        String aptTime = task.appointmentTime();
        String aptLoc = task.appointmentLocation();
        String aptContact = task.appointmentContact();
        Map<String, Object> appointment = mapOf(body.get("appointment"));
        if (!appointment.isEmpty()) {
            LocalDateTime t = parseTime(str(appointment.get("time")), "appointment.time");
            if (t != null) {
                aptTime = t.toString();
            }
            if (!isBlank(str(appointment.get("location")))) {
                aptLoc = str(appointment.get("location"));
            }
            if (!isBlank(str(appointment.get("contact")))) {
                aptContact = str(appointment.get("contact"));
            }
        }
        String status = promote(task.status(), (aptTime != null || !isBlank(aptLoc)) ? "BOOKED" : task.status());
        Task updated = new Task(taskId, task.customerId(), task.loanNo(), task.inspectType(), inspectors,
                aptTime, aptLoc, aptContact, version, status, outlineOf(task.inspectType(), version), task.createdAt());
        tasks.put(taskId, updated);
        idemRecord("task", requestId, fingerprint, view(updated));
        log.info("下户任务更新: taskId={}, status={}->{}, version={}, requestId={}",
                taskId, task.status(), status, version, requestId);
        return view(updated);
    }

    /** 任务开始：PENDING/BOOKED → IN_PROGRESS（须先预约） */
    private OnsiteInspectDtos.TaskView startTask(Map<String, Object> body, String requestId) {
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填");
        }
        Task task = requireTask(taskId);
        String fingerprint = canonical(body);
        OnsiteInspectDtos.TaskView cached = idemPrologue("task", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        if (!"PENDING".equals(task.status()) && !"BOOKED".equals(task.status())) {
            throw new BaiException(OnsiteInspectErrorCodes.STATE_CONFLICT,
                    "OIS_4006 状态流转非法：当前 " + task.status() + " 不可开始（须待预约/已预约）");
        }
        return transition(task, "IN_PROGRESS", requestId, fingerprint, "任务开始");
    }

    /** 任务完成：IN_PROGRESS → COMPLETED */
    private OnsiteInspectDtos.TaskView completeTask(Map<String, Object> body, String requestId) {
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填");
        }
        Task task = requireTask(taskId);
        String fingerprint = canonical(body);
        OnsiteInspectDtos.TaskView cached = idemPrologue("task", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        if (!"IN_PROGRESS".equals(task.status())) {
            throw new BaiException(OnsiteInspectErrorCodes.STATE_CONFLICT,
                    "OIS_4006 状态流转非法：当前 " + task.status() + " 不可完成（须先开始进入进行中）");
        }
        return transition(task, "COMPLETED", requestId, fingerprint, "任务完成");
    }

    /** 任务异常终止：任一非终态 → ABORTED */
    private OnsiteInspectDtos.TaskView abortTask(Map<String, Object> body, String requestId) {
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填");
        }
        Task task = requireTask(taskId);
        String fingerprint = canonical(body);
        OnsiteInspectDtos.TaskView cached = idemPrologue("task", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        return transition(task, "ABORTED", requestId, fingerprint, "任务异常终止");
    }

    /** 状态流转（整条替换+幂等记录） */
    private OnsiteInspectDtos.TaskView transition(Task task, String target, String requestId,
                                                  String fingerprint, String desc) {
        Task updated = new Task(task.taskId(), task.customerId(), task.loanNo(), task.inspectType(),
                task.inspectors(), task.appointmentTime(), task.appointmentLocation(), task.appointmentContact(),
                task.checklistVersion(), target, task.outline(), task.createdAt());
        tasks.put(task.taskId(), updated);
        idemRecord("task", requestId, fingerprint, view(updated));
        log.info("{}: taskId={}, {} -> {}, requestId={}", desc, task.taskId(), task.status(), target, requestId);
        return view(updated);
    }

    // -------------------------------------------------------------- 查勘清单

    /** 查勘清单/提纲获取（inspectType 必填；COLLATERAL 时 collateralType 必填；version 默认当前生效） */
    public OnsiteInspectDtos.ChecklistResult checklist(String inspectType, String collateralType, String version) {
        if (isBlank(inspectType)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：inspectType 必填");
        }
        String type = inspectType.toUpperCase();
        if (!INSPECT_TYPES.contains(type)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：inspectType 仅支持 PRE_LOAN/POST_LOAN/COLLATERAL/MOBILE_OUTREACH");
        }
        if (isBlank(version)) {
            version = "V1.0";
        }
        if (!CHECKLIST_VERSIONS.contains(version)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：查勘清单版本不存在（当前生效 V1.0/V2.0）");
        }
        List<OnsiteInspectDtos.ChecklistItem> items;
        if ("COLLATERAL".equals(type)) {
            if (isBlank(collateralType)) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数缺失：collateralType 必填（抵押物核查）");
            }
            items = collateralItems(collateralType);
        } else {
            items = fiveViewItems();
        }
        return new OnsiteInspectDtos.ChecklistResult(version, "OUTL-" + type + "-" + version, items);
    }

    /** 经营五看观察点（FR-02，必采全覆盖） */
    private List<OnsiteInspectDtos.ChecklistItem> fiveViewItems() {
        return List.of(
                new OnsiteInspectDtos.ChecklistItem("FV01", "看生产", true, "产能利用率/开工/订单执行情况"),
                new OnsiteInspectDtos.ChecklistItem("FV02", "看库存", true, "原材料/成品/出入库台账"),
                new OnsiteInspectDtos.ChecklistItem("FV03", "看人", true, "用工规模/在岗情况/老板访谈"),
                new OnsiteInspectDtos.ChecklistItem("FV04", "看单据", true, "合同/票据/账册要素"),
                new OnsiteInspectDtos.ChecklistItem("FV05", "看环境", true, "厂区/水电/设备运行状态"));
    }

    /** 抵押物查勘点位（FR-03，按抵押物类型：权属/物理状态/占用/价值相符性，拍照留痕） */
    private List<OnsiteInspectDtos.ChecklistItem> collateralItems(String collateralType) {
        return switch (collateralType) {
            case "不动产" -> List.of(
                    new OnsiteInspectDtos.ChecklistItem("CL01", "权属", true, "权属文件核验（证照与台账一致）"),
                    new OnsiteInspectDtos.ChecklistItem("CL02", "物理状态", true, "外观/结构/维护情况"),
                    new OnsiteInspectDtos.ChecklistItem("CL03", "占用", true, "实际占用/租赁情况"),
                    new OnsiteInspectDtos.ChecklistItem("CL04", "周边环境", true, "地段/配套/变现环境"),
                    new OnsiteInspectDtos.ChecklistItem("CL05", "价值相符性", true, "与台账评估比对"));
            case "动产" -> List.of(
                    new OnsiteInspectDtos.ChecklistItem("CL01", "权属", true, "权属文件/购买凭证核验"),
                    new OnsiteInspectDtos.ChecklistItem("CL02", "物理状态", true, "实物与台账清单比对"),
                    new OnsiteInspectDtos.ChecklistItem("CL03", "存放", true, "存放地点/保管条件"),
                    new OnsiteInspectDtos.ChecklistItem("CL04", "价值相符性", true, "与台账评估比对"));
            case "设备" -> List.of(
                    new OnsiteInspectDtos.ChecklistItem("CL01", "权属", true, "发票/登记证核验"),
                    new OnsiteInspectDtos.ChecklistItem("CL02", "物理状态", true, "外观/磨损/维修记录"),
                    new OnsiteInspectDtos.ChecklistItem("CL03", "运行", true, "运行状态/开机情况"),
                    new OnsiteInspectDtos.ChecklistItem("CL04", "价值相符性", true, "与台账评估比对"));
            case "车辆" -> List.of(
                    new OnsiteInspectDtos.ChecklistItem("CL01", "权属", true, "登记证/行驶证核验"),
                    new OnsiteInspectDtos.ChecklistItem("CL02", "物理状态", true, "车况/里程/外观"),
                    new OnsiteInspectDtos.ChecklistItem("CL03", "使用", true, "实际使用/停放情况"),
                    new OnsiteInspectDtos.ChecklistItem("CL04", "价值相符性", true, "与台账评估比对"));
            default -> throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：collateralType 仅支持 不动产/动产/设备/车辆");
        };
    }

    // -------------------------------------------------------------- 现场证据采集

    /** 现场证据采集/归集（幂等 taskId+mediaRef；时间位置一致性校验 → OIS_4104） */
    public OnsiteInspectDtos.EvidenceResult evidence(Map<String, Object> body, String requestId) {
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填");
        }
        Task task = requireTask(taskId);
        String evidenceType = str(body.get("evidenceType"));
        if (isBlank(evidenceType) || !EVIDENCE_TYPES.contains(evidenceType.toUpperCase())) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：evidenceType 仅支持 PHOTO/VIDEO/AUDIO/DOCUMENT/AUTHORIZATION");
        }
        String mediaRef = str(body.get("mediaRef"));
        if (isBlank(mediaRef)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：mediaRef 必填（媒资引用）");
        }
        String idemKey = taskId + "|" + mediaRef;
        String fingerprint = canonical(body);
        OnsiteInspectDtos.EvidenceResult cached = idemPrologue("evidence", idemKey, fingerprint);
        if (cached != null) {
            return cached;
        }
        String gps = str(body.get("gps"));
        String captureTime = str(body.get("captureTime"));
        if (!consistent(task, captureTime, gps)) {
            throw new BaiException(OnsiteInspectErrorCodes.EVIDENCE_INCONSISTENT,
                    "OIS_4104 证据时间位置与任务不一致（拍摄时间须在任务时间 ±2h 内，位置须在预约地点 5km 内）");
        }
        String quality = (isBlank(gps) || isBlank(captureTime)) ? "MEDIUM" : "HIGH";
        String evidenceId = "EVI" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        Evidence e = new Evidence(evidenceId, taskId, evidenceType.toUpperCase(), mediaRef, gps, captureTime,
                quality, true, true);
        evidences.put(evidenceId, e);
        OnsiteInspectDtos.EvidenceResult result = new OnsiteInspectDtos.EvidenceResult(evidenceId, taskId, true, quality, true);
        idemRecord("evidence", idemKey, fingerprint, result);
        log.info("现场证据采集: evidenceId={}, taskId={}, type={}, mediaRef={}, quality={}, requestId={}",
                evidenceId, taskId, evidenceType.toUpperCase(), mediaRef, quality, requestId);
        return result;
    }

    /** 时间位置一致性：拍摄时间在任务时间窗 ±2h、位置在预约地点 5km 内（无预约/缺参时默认一致） */
    private boolean consistent(Task task, String captureTime, String gps) {
        boolean ok = true;
        if (!isBlank(captureTime) && !isBlank(task.appointmentTime())) {
            LocalDateTime captured = parseTime(captureTime, "captureTime");
            LocalDateTime appointed = LocalDateTime.parse(task.appointmentTime());
            if (Math.abs(Duration.between(captured, appointed).toHours()) > EVIDENCE_WINDOW_HOURS) {
                ok = false;
            }
        }
        if (ok && !isBlank(gps) && !isBlank(task.appointmentLocation())) {
            try {
                if (distanceKm(gps, task.appointmentLocation()) > EVIDENCE_RADIUS_KM) {
                    ok = false;
                }
            } catch (IllegalArgumentException e) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：gps 须为 lat,lon（如 116.40,39.90）");
            }
        }
        return ok;
    }

    /** Haversine 球面距离（km） */
    private double distanceKm(String gpsA, String gpsB) {
        double[] a = parseGps(gpsA);
        double[] b = parseGps(gpsB);
        double lat1 = a[0] * Math.PI / 180, lon1 = a[1] * Math.PI / 180;
        double lat2 = b[0] * Math.PI / 180, lon2 = b[1] * Math.PI / 180;
        double dLat = lat2 - lat1, dLon = lon2 - lon1;
        double h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.pow(Math.sin(dLon / 2), 2);
        return 6371.0 * 2 * Math.asin(Math.sqrt(h));
    }

    private double[] parseGps(String gps) {
        String[] parts = gps.split(",");
        if (parts.length != 2) {
            throw new IllegalArgumentException("bad gps");
        }
        return new double[]{Double.parseDouble(parts[0].trim()), Double.parseDouble(parts[1].trim())};
    }

    // -------------------------------------------------------------- 访谈采集

    /** 访谈采集与双录登记（layer 三层；双录须客户授权文件 → OIS_4103；幂等 requestId） */
    public OnsiteInspectDtos.InterviewResult interview(Map<String, Object> body, String requestId) {
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填");
        }
        Task task = requireTask(taskId);
        String layer = str(body.get("layer"));
        if (isBlank(layer) || !LAYERS.contains(layer.toUpperCase())) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：layer 仅支持 BOSS/FINANCE/LINE（老板战略/财务数据/一线细节）");
        }
        List<String> questions = listOf(body.get("questions"));
        if (questions.isEmpty()) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：questions 至少 1 题");
        }
        String recordingRef = str(body.get("recordingRef"));
        String authorizationRef = str(body.get("authorizationRef"));
        if (!isBlank(recordingRef) && isBlank(authorizationRef)) {
            throw new BaiException(OnsiteInspectErrorCodes.RECORDING_AUTH_MISSING,
                    "OIS_4103 双录授权缺失：访谈双录须客户知情同意授权文件（authorizationRef）");
        }
        String fingerprint = canonical(body);
        OnsiteInspectDtos.InterviewResult cached = idemPrologue("interview", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        boolean recorded = !isBlank(recordingRef);
        String interviewId = "IVW" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        String transcriptRef = recorded ? (isBlank(str(body.get("transcript"))) ? "TRAN-" + interviewId : str(body.get("transcript"))) : null;
        String notes = layer.toUpperCase() + " 层访谈 " + questions.size() + " 题，"
                + (recorded ? "已双录登记（授权 " + authorizationRef + "）" : "未双录（仅要点录入）");
        Interview iv = new Interview(interviewId, taskId, layer.toUpperCase(), questions.size(),
                recordingRef, authorizationRef, transcriptRef);
        interviews.put(interviewId, iv);
        OnsiteInspectDtos.InterviewResult result = new OnsiteInspectDtos.InterviewResult(
                interviewId, taskId, layer.toUpperCase(), recorded, transcriptRef, notes);
        idemRecord("interview", requestId, fingerprint, result);
        log.info("访谈采集: interviewId={}, taskId={}, layer={}, questions={}, recorded={}, requestId={}",
                interviewId, taskId, layer.toUpperCase(), questions.size(), recorded, requestId);
        return result;
    }

    // -------------------------------------------------------------- 现场结论生成

    /** 现场结论与素材生成（五看结论必填；异常项证据须完整 → OIS_4102；幂等 requestId） */
    public OnsiteInspectDtos.SummaryResult summary(Map<String, Object> body, String requestId) {
        if (body == null) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：请求体必填");
        }
        String taskId = str(body.get("taskId"));
        if (isBlank(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：taskId 必填");
        }
        Task task = requireTask(taskId);
        Map<String, String> fiveViews = fiveViewsOf(body.get("fiveViews"));
        if (fiveViews.isEmpty()) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID, "OIS_4001 参数缺失：fiveViews 必填（五看结论）");
        }
        boolean hasAbnormalView = fiveViews.values().stream().anyMatch(v -> "ABNORMAL".equals(v));
        if (hasAbnormalView && !hasEvidence(taskId)) {
            throw new BaiException(OnsiteInspectErrorCodes.EVIDENCE_MISSING,
                    "OIS_4102 必采证据缺失：五看异常项证据必须完整，须先采集现场证据（设计 6.3(2)）");
        }
        String collateral = str(body.get("collateralConclusion"));
        if (!isBlank(collateral) && !VIEW_LEVELS.contains(collateral.toUpperCase())) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：collateralConclusion 仅支持 NORMAL/ATTENTION/ABNORMAL（相符/关注/异常）");
        }
        List<OnsiteInspectDtos.Abnormal> abnormals = abnormalsOf(body.get("abnormals"));
        String templateVersion = str(body.get("templateVersion"));
        if (isBlank(templateVersion)) {
            templateVersion = "V1.0";
        }
        String fingerprint = canonical(body);
        OnsiteInspectDtos.SummaryResult cached = idemPrologue("summary", requestId, fingerprint);
        if (cached != null) {
            return cached;
        }
        String conclusion = conclude(fiveViews, collateral, abnormals);
        String summaryId = "SUM" + TS.format(LocalDateTime.now()) + String.format("%04d", seq.incrementAndGet() % 10000);
        String materialRef = "MAT-" + summaryId;
        Summary s = new Summary(summaryId, taskId, conclusion, materialRef, abnormals, templateVersion);
        summaries.put(summaryId, s);
        OnsiteInspectDtos.SummaryResult result = new OnsiteInspectDtos.SummaryResult(
                summaryId, taskId, conclusion, materialRef, abnormals, templateVersion);
        idemRecord("summary", requestId, fingerprint, result);
        log.info("现场结论生成: summaryId={}, taskId={}, conclusion={}, materialRef={}, abnormals={}, requestId={}",
                summaryId, taskId, conclusion, materialRef, abnormals.size(), requestId);
        return result;
    }

    /** 总体结论（设计 6.3(2)+7.3）：严重异常/五看异常/查勘异常→ABNORMAL；关注或提示警告→ATTENTION；否则 NORMAL */
    private String conclude(Map<String, String> fiveViews, String collateral,
                            List<OnsiteInspectDtos.Abnormal> abnormals) {
        boolean severe = abnormals.stream().anyMatch(a -> "严重".equals(a.level()));
        boolean viewAbn = fiveViews.values().stream().anyMatch(v -> "ABNORMAL".equals(v));
        boolean collAbn = collateral != null && "ABNORMAL".equals(collateral.toUpperCase());
        if (severe || viewAbn || collAbn) {
            return "ABNORMAL";
        }
        boolean warning = !abnormals.isEmpty();
        boolean viewAtt = fiveViews.values().stream().anyMatch(v -> "ATTENTION".equals(v));
        boolean collAtt = collateral != null && "ATTENTION".equals(collateral.toUpperCase());
        if (warning || viewAtt || collAtt) {
            return "ATTENTION";
        }
        return "NORMAL";
    }

    /** 五看结论解析：key 支持 看生产/生产 归一，value 限 NORMAL/ATTENTION/ABNORMAL */
    private Map<String, String> fiveViewsOf(Object raw) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) {
            return out;
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String key = str(e.getKey());
            if (key != null && key.startsWith("看")) {
                key = key.substring(1);
            }
            if (!FIVE_VIEW_KEYS.contains(key)) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：fiveViews 键仅支持 生产/库存/人/单据/环境");
            }
            String value = str(e.getValue());
            if (isBlank(value) || !VIEW_LEVELS.contains(value.toUpperCase())) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：fiveViews 结论仅支持 NORMAL/ATTENTION/ABNORMAL");
            }
            out.put(key, value.toUpperCase());
        }
        return out;
    }

    /** 异常清单解析（AB01-AB06，等级 提示/警告/严重） */
    private List<OnsiteInspectDtos.Abnormal> abnormalsOf(Object raw) {
        if (raw == null || !(raw instanceof List<?> list)) {
            return List.of();
        }
        List<OnsiteInspectDtos.Abnormal> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：abnormals 项须为对象（type/level/description）");
            }
            String type = str(m.get("type"));
            if (isBlank(type) || !ABNORMAL_TYPES.contains(type.toUpperCase())) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：abnormal.type 仅支持 AB01-AB06（异常识别规则表）");
            }
            String level = str(m.get("level"));
            if (isBlank(level) || !ABNORMAL_LEVELS.contains(level)) {
                throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                        "OIS_4001 参数非法：abnormal.level 仅支持 提示/警告/严重");
            }
            out.add(new OnsiteInspectDtos.Abnormal(type.toUpperCase(), level, str(m.get("description"))));
        }
        return out;
    }

    // -------------------------------------------------------------- 内部工具

    /** 幂等前置：同幂等键参数不同 → OIS_4004 幂等冲突；参数一致且命中缓存 → 返回首次结果（重试安全） */
    private <T> T idemPrologue(String atom, String key, String fingerprint) {
        String fpKey = atom + ":" + key;
        String prev = idemFingerprints.get(fpKey);
        if (prev != null && !prev.equals(fingerprint)) {
            throw new BaiException(OnsiteInspectErrorCodes.IDEMPOTENCY_CONFLICT,
                    "OIS_4004 幂等冲突：幂等键 " + fpKey + " 重复且参数不同，请使用原请求号查询");
        }
        return idempotency.get(atom, key);
    }

    /** 幂等落库：指纹 + 首次结果 */
    private <T> void idemRecord(String atom, String key, String fingerprint, T result) {
        idemFingerprints.put(atom + ":" + key, fingerprint);
        idempotency.put(atom, key, result);
    }

    /** 任务查询：不存在或已终止 → OIS_4002 */
    private Task requireTask(String taskId) {
        Task task = tasks.get(taskId);
        if (task == null || TERMINAL.contains(task.status())) {
            throw new BaiException(OnsiteInspectErrorCodes.TASK_INVALID,
                    "OIS_4002 任务不存在或已终止：" + taskId);
        }
        return task;
    }

    /** 状态只进不退（设计 9.1）：PENDING<BOOKED<IN_PROGRESS<终态 */
    private String promote(String current, String target) {
        return rank(target) > rank(current) ? target : current;
    }

    private int rank(String status) {
        return switch (status) {
            case "PENDING" -> 0;
            case "BOOKED" -> 1;
            case "IN_PROGRESS" -> 2;
            case "COMPLETED", "ABORTED" -> 3;
            default -> -1;
        };
    }

    /** 调查提纲生成（设计 6.3(1)）：按尽调类型选基础提纲要点，保留版本与提纲引用 */
    private OnsiteInspectDtos.Outline outlineOf(String inspectType, String version) {
        List<String> points = switch (inspectType) {
            case "PRE_LOAN" -> List.of(
                    "双人成行：两名调查人资质核验并签字",
                    "经营五看：看生产/看库存/看人/看单据/看环境",
                    "三层访谈：老板战略/财务数据/一线细节",
                    "抵押物查勘（如有）：权属/物理状态/占用/价值相符性",
                    "财务数据交叉验证（financial-statement 协同）");
            case "POST_LOAN" -> List.of(
                    "贷后经营回访：经营变化/开工率/用工",
                    "五看回访：生产/库存/人/单据/环境",
                    "三层访谈：老板战略/财务数据/一线细节",
                    "单据与流水比对（核心/流水系统）",
                    "异常预警与处置联动");
            case "COLLATERAL" -> List.of(
                    "抵押物实地核查：权属文件核验",
                    "物理状态查勘：外观/结构/维护",
                    "占用情况核查：实际占用/租赁",
                    "价值相符性：与台账评估比对",
                    "拍照留痕：点位/朝向/时间水印");
            case "MOBILE_OUTREACH" -> List.of(
                    "移动展业外拓：线索采集与画像",
                    "经营五看：快速观察记录",
                    "访谈：老板战略/一线细节",
                    "定位打卡与离线采集（弱网缓存）");
            default -> List.of();
        };
        return new OnsiteInspectDtos.Outline("OUTL-" + inspectType + "-" + version, version, points);
    }

    private OnsiteInspectDtos.TaskView view(Task task) {
        return new OnsiteInspectDtos.TaskView(task.taskId(), task.customerId(), task.loanNo(), task.inspectType(),
                task.inspectors(), task.status(), task.inspectors().size() >= 2, task.outline(), task.createdAt());
    }

    /** 双人调查人解析：去重且剔除空值，不足 2 人由调用方抛 OIS_4101 */
    private List<String> inspectorsOf(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数缺失：inspectors 必填（双人调查人数组）");
        }
        return list.stream().map(OnsiteInspectService::str).filter(s -> !isBlank(s)).map(String::trim).distinct().toList();
    }

    private List<String> listOf(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(OnsiteInspectService::str).filter(s -> !isBlank(s)).toList();
    }

    private Map<String, Object> mapOf(Object raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> m) {
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
        }
        return out;
    }

    /** ISO 时间解析：非法 → OIS_4001 */
    private LocalDateTime parseTime(String s, String field) {
        if (isBlank(s)) {
            return null;
        }
        try {
            return LocalDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new BaiException(OnsiteInspectErrorCodes.PARAM_INVALID,
                    "OIS_4001 参数非法：" + field + " 须为 ISO 时间（如 2026-11-10T10:00:00）");
        }
    }

    private boolean hasEvidence(String taskId) {
        return evidences.values().stream().anyMatch(e -> e.taskId().equals(taskId));
    }

    /** 幂等指纹：请求参数规范化（键排序+值大写），同 requestId 不同参数判定冲突 */
    private String canonical(Map<String, Object> body) {
        if (body == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        body.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e -> sb.append(e.getKey()).append('=').append(stringify(e.getValue())).append(';'));
        return sb.toString();
    }

    private String stringify(Object v) {
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            m.entrySet().stream().sorted(Comparator.comparing(e -> String.valueOf(e.getKey())))
                    .forEach(e -> sb.append(e.getKey()).append('=').append(stringify(e.getValue())).append(','));
            return sb.append('}').toString();
        }
        if (v instanceof List<?> l) {
            return String.valueOf(l);
        }
        return v == null ? "" : String.valueOf(v).toUpperCase();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
