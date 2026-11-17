package com.bai.image;

import java.util.UUID;

/**
 * Mock 引擎提供者：内容哈希确定性仿真（联调/回归专用）。
 * <p>五维评分由图像内容 MD5 指纹驱动，同入参完全复现，行为与改造前仿真版一致：
 * 综合分=0.3×clarity+0.15×brightness+0.15×contrast+0.2×(1-blur)+0.2×(1-occlusion)。
 */
public class MockImageProvider implements ImageProvider {

    @Override
    public ImageDtos.QualityInfo check(byte[] content, String requestId) {
        return qualityOf(md5Hex(content));
    }

    /** 五维评分（内容哈希确定性生成）：综合分=0.3×clarity+0.15×brightness+0.15×contrast+0.2×(1-blur)+0.2×(1-occlusion) */
    private ImageDtos.QualityInfo qualityOf(String srcHash) {
        int h = srcHash.hashCode();
        double clarity = 0.70 + Math.abs(h % 30) / 100.0;
        double brightness = 0.60 + Math.abs((h >> 5) % 40) / 100.0;
        double contrast = 0.55 + Math.abs((h >> 10) % 45) / 100.0;
        double blur = Math.abs((h >> 15) % 10) / 100.0;
        double occlusion = Math.abs((h >> 20) % 5) / 100.0;
        double score = Math.round((0.3 * clarity + 0.15 * brightness + 0.15 * contrast
                + 0.2 * (1 - blur) + 0.2 * (1 - occlusion)) * 100) / 100.0;
        String suggestion = score >= 0.80 ? "ACCEPT" : score >= 0.60 ? "RETRY" : "REJECT";
        return new ImageDtos.QualityInfo(round2(clarity), round2(brightness), round2(contrast),
                round2(blur), round2(occlusion), score, score >= 0.60, suggestion);
    }

    private static String md5Hex(byte[] bytes) {
        return UUID.nameUUIDFromBytes(bytes).toString().replace("-", "");
    }

    private static double round2(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
