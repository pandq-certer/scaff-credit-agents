package com.bai.face;

import com.bai.common.BaiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FaceVerifyService 单元测试（感知类代表性原子）：纯 1:1 人证比对、1:N 检索、视频异步任务、参数校验（确定性仿真）。
 */
class FaceVerifyServiceTest {

    private final FaceVerifyService service = new FaceVerifyService(new ObjectMapper());

    private MultipartFile image() {
        return new MockMultipartFile("image", "face.jpg", "image/jpeg", new byte[2048]);
    }

    private MultipartFile idPhoto() {
        return new MockMultipartFile("idPhoto", "id.jpg", "image/jpeg", new byte[2048]);
    }

    @Test
    void 人证比对_阈值固定得分确定() {
        FaceVerifyDtos.VerifyResult r = service.compare(image(), idPhoto(), "general", "fac-c1");
        assertNotNull(r.compare());
        assertEquals(0.75, r.compare().threshold());
        assertTrue(r.compare().score() >= 0.70 && r.compare().score() <= 0.99);
        assertTrue(r.conclusion().equals("PASS") || r.conclusion().equals("REJECT"));
        assertEquals("general", r.scene());
    }

    @Test
    void 同requestId_比对幂等() {
        String rid = "fac-idem";
        FaceVerifyDtos.VerifyResult a = service.compare(image(), idPhoto(), "general", rid);
        FaceVerifyDtos.VerifyResult b = service.compare(image(), idPhoto(), "general", rid);
        assertEquals(a.verifyId(), b.verifyId(), "同 requestId 应复用首次结果");
        assertEquals(a.compare().score(), b.compare().score());
    }

    @Test
    void N检索_返回topK降序候选() {
        FaceVerifyDtos.VerifyResult r = service.searchFace(image(), "blacklist", 5, "fac-s1");
        assertNotNull(r.search());
        assertEquals(5, r.search().top().size());
        for (FaceVerifyDtos.Candidate c : r.search().top()) {
            assertTrue(c.id().startsWith("BLK_"));
        }
        assertTrue(r.search().top().get(0).distance() <= r.search().top().get(4).distance());
    }

    @Test
    void 检索_library非法抛FAC1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.searchFace(image(), "unknown-lib", 10, "fac-x1"));
        assertEquals(FaceVerifyErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 检索_topK越界抛FAC1001() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.searchFace(image(), "history", 0, "fac-x2"));
        assertEquals(FaceVerifyErrorCodes.PARAM_INVALID, e.getCode());
    }

    @Test
    void 比对_缺底片抛FAC1004() {
        BaiException e = assertThrows(BaiException.class,
                () -> service.compare(image(), null, "general", "fac-x3"));
        assertEquals(FaceVerifyErrorCodes.NOT_FOUND, e.getCode());
    }

    @Test
    void 视频核身_任务可查询() {
        MultipartFile video = new MockMultipartFile("image", "video.mp4", "video/mp4", new byte[2048]);
        FaceVerifyDtos.VerifyResult r = service.verify(video, "general", idPhoto(), null, false, null, "fac-v1");
        FaceVerifyDtos.VerifyTask t = service.getTask(r.verifyId());
        assertEquals("DONE", t.status());
        assertEquals(r.verifyId(), t.taskId());
    }

    @Test
    void 图像过小_抛FAC1007() {
        MultipartFile tiny = new MockMultipartFile("image", "tiny.jpg", "image/jpeg", new byte[100]);
        BaiException e = assertThrows(BaiException.class,
                () -> service.compare(tiny, idPhoto(), "general", "fac-x4"));
        assertEquals(FaceVerifyErrorCodes.NO_FACE, e.getCode());
    }

    @Test
    void 任务不存在_抛FAC1004() {
        BaiException e = assertThrows(BaiException.class, () -> service.getTask("NO-TASK"));
        assertEquals(FaceVerifyErrorCodes.NOT_FOUND, e.getCode());
    }
}
