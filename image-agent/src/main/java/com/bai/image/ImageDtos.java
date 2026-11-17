package com.bai.image;

import java.util.List;
import java.util.Map;

/**
 * 图像处理输出统一结构（提示词库 A-04：ProcessResult/QualityInfo/HashInfo/CompareResult/TaskInfo）
 * <p>综合分=0.3×clarity+0.15×brightness+0.15×contrast+0.2×(1-blur)+0.2×(1-occlusion)；
 * ≥0.80 ACCEPT、0.60—0.80 RETRY、&lt;0.60 REJECT；感知哈希汉明距离≤10 判定相似
 */
public final class ImageDtos {

    private ImageDtos() {}

    /** 五维质量评分与判定 */
    public record QualityInfo(double clarity, double brightness, double contrast, double blur,
                              double occlusion, double score, boolean usable, String suggestion) {}

    /** 感知哈希指纹（dHash/pHash） */
    public record HashInfo(String dHash, String pHash) {}

    /** 单页处理结果 */
    public record PageResult(int page, int width, int height, String format, int sizeKb,
                             QualityInfo quality, String imageUrl, HashInfo hash, boolean masked) {}

    /** 处理管道结果（同步返回；超分或 &gt;5MB 自动转异步并附 taskId） */
    public record ProcessResult(String imageId, String scene, String taskId, List<PageResult> pages, int elapsedMs) {}

    /** 质量评估结果 */
    public record QualityResult(String imageId, String scene, QualityInfo quality, HashInfo hash, int elapsedMs) {}

    /** 相似度比对结果（汉明距离≤10 判相似） */
    public record CompareResult(String leftRef, String rightRef, int distance, boolean similar, int elapsedMs) {}

    /** 异步任务查询结果 */
    public record TaskInfo(String taskId, String status, String imageId, String scene, String createdAt) {}
}
