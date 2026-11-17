package com.bai.docparse;

/**
 * 仿真提供者（默认）：图像类文件不做真实 OCR，返回 null → 服务层走确定性仿真兜底
 * （文件名+大小+requestId 哈希推导），与历史版本行为零差异，联调回归基线。
 */
public class MockDocParseProvider implements DocParseProvider {

    @Override
    public String ocrImage(byte[] content, String filename, String requestId) {
        return null;
    }
}
