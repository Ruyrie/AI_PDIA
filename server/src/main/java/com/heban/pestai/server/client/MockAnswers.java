package com.heban.pestai.server.client;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.heban.pestai.server.common.ChatRole;
import com.heban.pestai.server.common.Rules;

/**
 * Mock 问答的固定回答：按识别结论或问题中的关键词挑选一段农技回答，格式只用加粗、小标题行、编号列表。
 */
final class MockAnswers {

    static final String CONCLUSION_MARK = ChatPrompts.CONCLUSION_MARK;
    static final String MOCK_NOTE = "（当前为模拟回答：服务器未配置大模型 Key，配置后由真实模型生成。）";

    /** 关键词 → 回答正文（不含开头与结尾） */
    private static final Map<String, String> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("霜霉", """
                处理建议
                1. 上午通风排湿，减少叶面结露时间，棚内湿度尽量控制在 85% 以下
                2. 摘除病叶，带出棚外集中处理，不要随手丢在棚内
                3. 发病初期可选 **烯酰吗啉** 或 **霜脲·锰锌** 等药剂，按农药标签推荐用量喷施，重点喷叶背，7 天左右一次
                预防要点
                1. 浇水选晴天上午，避免大水漫灌
                2. 注意轮换不同作用机理的药剂，延缓抗药性""");
        TOPICS.put("早疫", """
                处理建议
                1. 及时摘除下部病叶、老叶，改善株间通风透光
                2. 发病初期可选 **苯醚甲环唑** 或 **代森锰锌** 等药剂，按农药标签推荐用量喷施，7–10 天一次
                3. 增施磷钾肥，避免偏施氮肥，提高植株抗性
                预防要点
                1. 与非茄科作物轮作 2–3 年
                2. 雨后及时排水，降低田间湿度""");
        TOPICS.put("蚜", """
                处理建议
                1. 虫量少时可剪除虫口密集的嫩梢，或用清水冲洗叶背
                2. 悬挂 **黄色粘虫板**，每亩 20–30 张，诱杀有翅蚜
                3. 虫量大时可选 **吡虫啉** 或 **啶虫脒** 等药剂，按农药标签推荐用量喷施，注意安全间隔期
                预防要点
                1. 清除田边杂草，减少蚜虫越冬场所
                2. 保护瓢虫、草蛉等天敌，尽量少用广谱杀虫剂""");
        TOPICS.put("白粉", """
                处理建议
                1. 摘除病叶，保持株间通风透光
                2. 发病初期可选 **醚菌酯** 或 **乙嘧酚** 等药剂，按农药标签推荐用量喷施
                3. 喷药要均匀覆盖叶片正反面，7–10 天一次，连续 2–3 次""");
        TOPICS.put("发黄", """
                可能原因
                1. **缺氮或缺镁**：老叶先黄、叶脉间失绿，多与施肥不足或土壤偏酸有关
                2. **根系受损**：浇水过多、土壤板结或地下害虫为害，会导致整株叶片发黄萎蔫
                3. **病害**：叶片出现病斑、霉层或卷曲时，多为病害引起
                处理建议
                1. 先检查根系与叶背，排除病虫为害
                2. 缺素时叶面喷施 **0.2% 硫酸镁** 或尿素水溶液，配合追施复合肥
                3. 控制浇水，雨后及时排水，保持土壤疏松""");
        TOPICS.put("杂草", """
                处理建议
                1. 杂草 3–5 叶期前清除效果最好，结籽前一定要拔除
                2. 小面积可人工拔除或中耕除草，大面积可选择对作物安全的除草剂，按标签用量使用
                3. 覆盖地膜或秸秆，能明显减少杂草出苗""");
        TOPICS.put("拌种", """
                处理建议
                1. 播种前选用 **苯醚甲环唑** 或 **咯菌腈** 等种衣剂拌种，可预防纹枯病、根腐病等土传病害
                2. 地下害虫多的地块可加 **吡虫啉** 拌种
                3. 拌种后阴干再播，当天拌当天播，按种衣剂标签用量操作""");
    }

    private static final String GENERIC = """
            初步建议
            1. 先仔细观察叶片正反面、茎秆和根部，确认有无病斑、霉层、虫体或虫孔
            2. 拍一张清晰的近照，用「拍照识别」功能识别，可以得到更准确的判断
            3. 发现病株及时清除，带出田外处理，避免扩散
            日常管理
            1. 合理浇水施肥，避免大水漫灌和偏施氮肥
            2. 保持通风透光，定期巡查，做到早发现、早处理""";

    private MockAnswers() {
    }

    static String answer(List<ChatMessage> messages) {
        String question = MockChatClient.lastUserText(messages);
        boolean withImage = !messages.isEmpty() && messages.get(messages.size() - 1).hasImage();
        String conclusion = conclusionTopName(messages);

        StringBuilder sb = new StringBuilder();
        if (withImage) {
            sb.append("从照片看，");
        }
        if (conclusion != null) {
            sb.append("结合识别结果，初步判断为 **").append(conclusion).append("**，下面是针对性的建议。\n");
        } else {
            sb.append("关于「").append(Rules.truncate(question.strip(), 20)).append("」，可以按下面的思路处理。\n");
        }
        String topicKey = conclusion != null ? conclusion + question : question;
        sb.append(pickTopic(topicKey)).append('\n');
        sb.append("以上建议仅供参考，用药请按农药标签推荐用量使用，病情严重时建议咨询当地农技站。\n");
        sb.append(MOCK_NOTE);
        return sb.toString();
    }

    /** 模拟的思考过程（一小段，先于正文推送，便于 App 联调思考区） */
    static String reasoning(List<ChatMessage> messages) {
        String question = MockChatClient.lastUserText(messages);
        int images = messages.isEmpty() ? 0 : messages.get(messages.size() - 1).imageDataUrls().size();
        String conclusion = conclusionTopName(messages);

        StringBuilder sb = new StringBuilder();
        if (images == 1) {
            sb.append("用户发来一张照片，先看叶片、茎秆上有没有病斑、霉层或虫体。");
        } else if (images > 1) {
            sb.append("用户发来 ").append(images).append(" 张照片，逐张看叶片、茎秆上有没有病斑、霉层或虫体。");
        }
        sb.append("用户问的是「").append(Rules.truncate(question.strip(), 20)).append("」。");
        if (conclusion != null) {
            sb.append("识别结果是").append(conclusion).append("，回答要围绕它展开。");
        }
        sb.append("\n先判断属于病害、虫害还是管理问题，再按处理建议、预防要点分条回答；")
                .append("提到药剂时提醒按农药标签用量使用。");
        return sb.toString();
    }

    private static String pickTopic(String text) {
        for (Map.Entry<String, String> entry : TOPICS.entrySet()) {
            if (text.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        if (text.contains("黄") || text.contains("卷")) {
            return TOPICS.get("发黄");
        }
        if (text.contains("除草") || text.contains("马唐") || text.contains("苋")) {
            return TOPICS.get("杂草");
        }
        if (text.contains("播种")) {
            return TOPICS.get("拌种");
        }
        return GENERIC;
    }

    /** 从系统提示中取出识别结论的名称（「本次对话针对的识别结论：黄瓜霜霉病，……」） */
    static String conclusionTopName(List<ChatMessage> messages) {
        for (ChatMessage m : messages) {
            if (!ChatRole.SYSTEM.equals(m.role()) || m.text() == null) {
                continue;
            }
            int start = m.text().indexOf(CONCLUSION_MARK);
            if (start < 0) {
                continue;
            }
            String rest = m.text().substring(start + CONCLUSION_MARK.length());
            int end = rest.indexOf('，');
            String name = (end > 0 ? rest.substring(0, end) : rest).strip();
            return name.isEmpty() ? null : name;
        }
        return null;
    }
}
