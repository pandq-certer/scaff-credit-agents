package com.bai.image;

/**
 * 图像质检引擎提供者抽象：隔离「引擎内核」与「服务编排」。
 * <p>mock = {@link MockImageProvider} 确定性仿真（联调/回归专用，行为与仿真版完全一致）；
 * real = {@link RealImageProvider} 统一 AI 推理网关（GPU 质检内核，HTTP 调用）。
 * <p>切换开关 {@code bai.image.provider=mock|real}，默认 mock。
 */
public interface ImageProvider {

    /**
     * 质量评估：图像字节 → 五维评分（清晰度/亮度/对比度/模糊/遮挡）与综合判定
     * （≥0.80 ACCEPT、0.60—0.80 RETRY、&lt;0.60 REJECT）。
     * <p>文件大小/损坏校验由服务层完成，实现只负责引擎评估。
     */
    ImageDtos.QualityInfo check(byte[] content, String requestId);
}
