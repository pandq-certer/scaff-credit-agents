package com.bai.image;

import com.bai.common.BaiException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImageService 单元测试（感知类代表性原子）：JSON 直连处理（确定性仿真）、质量评估、幂等、格式/参数校验。
 */
class ImageServiceTest {

    private final ImageService service = new ImageService();

    private Map<String, Object> processBody() {
        return Map.of("fileName", "cert-001.jpg",
                "steps", "[{\"name\":\"denoise\",\"enabled\":true}]", "scene", "cert");
    }

    @Test
    void 图像处理_按场景标准化输出() {
        ImageDtos.ProcessResult r = service.processJson(processBody(), "img-1");
        assertEquals("cert", r.scene());
        assertNull(r.taskId(), "小文件同步处理不应产生任务");
        ImageDtos.PageResult page = r.pages().get(0);
        assertEquals(2480, page.width());
        assertEquals(3508, page.height());
        assertEquals("jpg", page.format());
        assertFalse(page.masked());
        assertEquals(16, page.hash().dHash().length());
        assertEquals(16, page.hash().pHash().length());
    }

    @Test
    void 质量评估_确定性评分() {
        ImageDtos.QualityResult r = service.qualityJson(
                Map.of("fileName", "cert-001.jpg", "scene", "cert"), "img-q1");
        double score = r.quality().score();
        assertTrue(score >= 0.60 && score <= 1.0, "综合分公式下界 ≥0.7565，应判定可用");
        assertTrue(r.quality().usable());
        assertTrue(r.quality().suggestion().equals("ACCEPT") || r.quality().suggestion().equals("RETRY"));
    }

    @Test
    void 同fileName两次调用_哈希一致() {
        ImageDtos.ProcessResult a = service.processJson(processBody(), "img-d1");
        ImageDtos.ProcessResult b = service.processJson(processBody(), "img-d2");
        assertEquals(a.pages().get(0).hash(), b.pages().get(0).hash(), "同内容哈希应确定一致");
    }

    @Test
    void 同requestId_幂等复用首次结果() {
        String rid = "img-idem";
        ImageDtos.ProcessResult a = service.processJson(processBody(), rid);
        ImageDtos.ProcessResult b = service.processJson(processBody(), rid);
        assertEquals(a.imageId(), b.imageId(), "同 requestId 应复用首次结果");
    }

    @Test
    void 无扩展名_抛IMG1006() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.processJson(Map.of("fileName", "nofile"), "img-x1"));
        assertEquals(ImageErrorCodes.FORMAT_INVALID, e.getCode());
    }

    @Test
    void steps缺失_抛IMG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.processJson(Map.of("fileName", "a.jpg"), "img-x2"));
        assertEquals(ImageErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void scene非法_抛IMG1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.processJson(Map.of("fileName", "a.jpg",
                        "steps", "[{\"name\":\"denoise\",\"enabled\":true}]", "scene", "bad"), "img-x3"));
        assertEquals(ImageErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 任务不存在_抛IMG1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.task("NO-TASK"));
        assertEquals(ImageErrorCodes.NOT_FOUND, e.getCode());
    }

    @Test
    void 比对_处理记录不存在抛IMG1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.compare(null, null, "NO-IMG", null, "img-c1"));
        assertEquals(ImageErrorCodes.NOT_FOUND, e.getCode());
    }

    @Test
    void 文件超限_抛IMG1005() {
        MultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[21 * 1024 * 1024]);
        BaiException e = assertThrows(BaiException.class,
                () -> service.process(file, "[{\"name\":\"denoise\",\"enabled\":true}]", "cert",
                        null, null, "img-x4"));
        assertEquals(ImageErrorCodes.FILE_TOO_LARGE, e.getCode());
    }
}
