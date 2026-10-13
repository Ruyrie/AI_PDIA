package com.heban.pestai.server.client;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;

import com.heban.pestai.server.common.Mode;
import com.heban.pestai.server.config.PestaiProperties;

/**
 * 未配置 DASHSCOPE_API_KEY 时使用的识图模拟实现：按图片哈希在几条示例结果中轮换（同一张图结果稳定），
 * 按配置比例模拟「照片不清晰」（IMAGE_BAD，便于联调 R02），并模拟识图耗时。返回文本与真实模型相同，同样经过解析校验。
 */
public class MockVisionClient implements VisionClient {

    static final String IMAGE_BAD = "{\"status\":\"IMAGE_BAD\"}";

    static final List<String> PEST_SAMPLES = List.of(
            """
            {"status":"SUCCESS","top_name":"黄瓜霜霉病","confidence":0.92,"severity":"中度","category":"卵菌病害",\
            "part":"叶部","spread_fast":true,\
            "evidence":"叶面出现受叶脉限制的多角形黄褐色病斑，叶背有灰黑色霉层，符合霜霉病典型特征。建议 3 天内处理。",\
            "urgency_days":3,"others":[{"name":"黄瓜细菌性角斑病","confidence":0.06},{"name":"缺镁症（生理性）","confidence":0.02}]}""",
            """
            {"status":"SUCCESS","top_name":"番茄早疫病","confidence":0.87,"severity":"轻度","category":"真菌病害",\
            "part":"叶部","spread_fast":false,\
            "evidence":"下部老叶出现圆形至不规则形褐色病斑，病斑上有明显的同心轮纹，周围有黄色晕圈，符合早疫病特征。建议 5 天内处理。",\
            "urgency_days":5,"others":[{"name":"番茄晚疫病","confidence":0.08},{"name":"番茄叶霉病","confidence":0.03}]}""",
            """
            {"status":"SUCCESS","top_name":"桃蚜","confidence":0.95,"severity":"较重","category":"刺吸式害虫",\
            "part":"嫩梢","spread_fast":true,\
            "evidence":"嫩梢和叶背聚集大量绿色至黄绿色小型蚜虫，叶片向背面卷缩，可见蜜露与煤污，虫口密度较高，符合桃蚜为害特征。建议 2 天内处理。",\
            "urgency_days":2,"others":[{"name":"棉蚜","confidence":0.03},{"name":"烟粉虱","confidence":0.01}]}""",
            """
            {"status":"SUCCESS","top_name":"辣椒 · 健康植株","confidence":0.93,"severity":"健康","category":"健康植株",\
            "part":"全株","spread_fast":false,\
            "evidence":"叶片颜色浓绿均匀，叶面平展，无病斑、无虫体与虫孔，茎秆粗壮，未发现病虫害迹象。保持现有水肥管理，注意定期巡查。",\
            "urgency_days":null,"others":[]}""");

    static final List<String> CROP_SAMPLES = List.of(
            """
            {"status":"SUCCESS","top_name":"马唐（杂草）","confidence":0.90,"severity":null,"category":"禾本科杂草",\
            "part":"全株","spread_fast":true,\
            "evidence":"叶片条状披针形，茎基部匍匐、节上生根，穗由 3 到 10 枚指状分枝组成，符合马唐特征。建议 7 天内在 3 到 5 叶期前清除。",\
            "urgency_days":7,"others":[{"name":"牛筋草（杂草）","confidence":0.06},{"name":"稗草（杂草）","confidence":0.02}]}""",
            """
            {"status":"SUCCESS","top_name":"番茄（作物）","confidence":0.94,"severity":null,"category":"茄科作物",\
            "part":"全株","spread_fast":false,\
            "evidence":"叶片为奇数羽状复叶，小叶边缘有不规则锯齿，茎上密生腺毛，可见黄色小花，符合番茄形态特征。长势良好，注意及时搭架整枝。",\
            "urgency_days":null,"others":[{"name":"马铃薯（作物）","confidence":0.03}]}""",
            """
            {"status":"SUCCESS","top_name":"反枝苋（杂草）","confidence":0.88,"severity":null,"category":"阔叶杂草",\
            "part":"全株","spread_fast":true,\
            "evidence":"茎直立粗壮、密生短柔毛，叶片菱状卵形、叶脉明显，顶生穗状圆锥花序，符合反枝苋特征。建议 5 天内在结籽前拔除。",\
            "urgency_days":5,"others":[{"name":"凹头苋（杂草）","confidence":0.07},{"name":"藜（杂草）","confidence":0.03}]}""");

    private final PestaiProperties.Mock mock;
    private final MockChatClient chatDelegate;

    public MockVisionClient(PestaiProperties.Mock mock) {
        this.mock = mock;
        // 识图模型（深度思考默认关闭）不输出思考片段
        this.chatDelegate = new MockChatClient(mock, false);
    }

    @Override
    public String recognize(byte[] image, String mimeType, String mode, Duration timeout) throws VisionException {
        long delay = Math.max(0, mock.getVisionDelayMs());
        if (delay > timeout.toMillis()) {
            sleep(timeout.toMillis());
            throw new VisionException(VisionException.Reason.TIMEOUT, "模拟识图超时");
        }
        sleep(delay);
        int hash = hash(image);
        if (Math.floorMod(hash, 100) < mock.getImageBadPercent()) {
            return IMAGE_BAD;
        }
        List<String> samples = Mode.CROP.equals(mode) ? CROP_SAMPLES : PEST_SAMPLES;
        return samples.get(Math.floorMod(hash >>> 8, samples.size()));
    }

    @Override
    public void streamChat(List<ChatMessage> messages, StreamCallback callback, StreamControl control)
            throws ChatClientException {
        chatDelegate.stream(messages, callback, control);
    }

    @Override
    public boolean isMock() {
        return true;
    }

    static int hash(byte[] image) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(image == null ? new byte[0] : image);
            return ((digest[0] & 0xFF) << 24) | ((digest[1] & 0xFF) << 16) | ((digest[2] & 0xFF) << 8) | (digest[3] & 0xFF);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
