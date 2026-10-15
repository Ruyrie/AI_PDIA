package com.heban.pestai.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.data.StreamChunk;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.network.PageVO;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

/** 单元测试用的假 Repository：记录调用参数，结果可由测试设置 */
public class FakeAiRepository implements AiRepository {

    /** 一次 sendMessage 调用的参数 */
    public static final class SendCall {
        public final long sessionId;
        public final String content;
        public final List<String> imageUrls;
        public final boolean regenerate;

        SendCall(long sessionId, String content, List<String> imageUrls, boolean regenerate) {
            this.sessionId = sessionId;
            this.content = content;
            this.imageUrls = new ArrayList<>(imageUrls);
            this.regenerate = regenerate;
        }
    }

    /** 按本地 Uri 返回上传结果（可为每张图片返回不同的 SingleSubject，控制完成顺序） */
    public interface Upload {
        Single<String> upload(String localUri);
    }

    public interface Answer {
        Flowable<StreamChunk> answer(SendCall call);
    }

    public interface Pages {
        Single<PageVO<SessionItemVO>> page(int page, int size);
    }

    /** 按录音文件返回识别结果 */
    public interface Asr {
        Single<String> recognize(File wav);
    }

    public boolean guest;

    public long nextSessionId = 512;
    public final List<Long> createSessionRecordIds = new ArrayList<>();
    public Single<SessionVO> createSessionResult;

    public Single<String> uploadResult = Single.just("http://10.0.2.2:8080/files/chat/20261013/8f2c.jpg");
    public Upload upload = uri -> uploadResult;
    public final List<String> uploadCalls = new ArrayList<>();

    public final List<SendCall> sendCalls = new ArrayList<>();
    public Answer answer = call -> contents("从照片看，", "叶背霉层比上一张更密。");

    public Single<RecordItemVO> recordResult = Single.error(new IllegalStateException("not found"));
    public final List<Long> recordCalls = new ArrayList<>();

    public Pages pages = (page, size) -> Single.just(page(Collections.emptyList(), 0, false));
    public final List<Integer> pageCalls = new ArrayList<>();
    public Single<SessionDetailVO> sessionResult = Single.error(new IllegalStateException("not set"));
    public final List<Long> sessionCalls = new ArrayList<>();
    public Completable deleteResult = Completable.complete();
    public final List<Long> deleteCalls = new ArrayList<>();

    /** 语音识别默认成功 */
    public Single<String> asrResult = Single.just("番茄叶子发黄怎么办");
    public Asr asr = wav -> asrResult;
    public final List<File> asrCalls = new ArrayList<>();

    @Override
    public boolean isGuest() {
        return guest;
    }

    @NonNull
    @Override
    public Single<SessionVO> createSession(@Nullable Long recordId) {
        createSessionRecordIds.add(recordId);
        if (createSessionResult != null) {
            return createSessionResult;
        }
        SessionVO vo = new SessionVO();
        vo.setSessionId(nextSessionId);
        vo.setRecordId(recordId);
        return Single.just(vo);
    }

    @NonNull
    @Override
    public Single<String> uploadImage(@NonNull String localUri) {
        uploadCalls.add(localUri);
        return upload.upload(localUri);
    }

    @NonNull
    @Override
    public Flowable<StreamChunk> sendMessage(long sessionId, @NonNull String content, @NonNull List<String> imageUrls,
                                             boolean regenerate) {
        SendCall call = new SendCall(sessionId, content, imageUrls, regenerate);
        sendCalls.add(call);
        return answer.answer(call);
    }

    @NonNull
    @Override
    public Single<PageVO<SessionItemVO>> sessions(int page, int size) {
        pageCalls.add(page);
        return pages.page(page, size);
    }

    @NonNull
    @Override
    public Single<SessionDetailVO> session(long sessionId) {
        sessionCalls.add(sessionId);
        return sessionResult;
    }

    @NonNull
    @Override
    public Completable deleteSession(long sessionId) {
        deleteCalls.add(sessionId);
        return deleteResult;
    }

    @NonNull
    @Override
    public Single<RecordItemVO> record(long id) {
        recordCalls.add(id);
        return recordResult;
    }

    @NonNull
    @Override
    public Single<String> recognizeSpeech(@NonNull File wav) {
        asrCalls.add(wav);
        return asr.recognize(wav);
    }

    // ==================== 构造测试数据 ====================

    /** 上传结果地址：按本地 Uri 生成，便于断言顺序 */
    public static String urlOf(String localUri) {
        return "http://10.0.2.2:8080/files/chat/20261014/" + Math.abs(localUri.hashCode()) + ".jpg";
    }

    /** 立即成功的上传，地址为 {@link #urlOf(String)} */
    public static Single<String> urlSingle(String localUri) {
        return Single.just(urlOf(localUri));
    }

    /** 只有正文片段的回答 */
    public static Flowable<StreamChunk> contents(String... texts) {
        List<StreamChunk> list = new ArrayList<>();
        for (String t : texts) {
            list.add(StreamChunk.content(t));
        }
        return Flowable.fromIterable(list);
    }

    public static RecordItemVO record(long id, String status, String name, Double confidence, String severity) {
        RecordItemVO vo = new RecordItemVO();
        vo.setId(id);
        vo.setMode("PEST");
        vo.setStatus(status);
        vo.setTopName(name);
        vo.setConfidence(confidence);
        vo.setSeverity(severity);
        vo.setImageUrl("http://10.0.2.2:8080/files/rec/" + id + ".jpg");
        return vo;
    }

    public static PageVO<SessionItemVO> page(List<SessionItemVO> list, int total, boolean hasMore) {
        PageVO<SessionItemVO> page = new PageVO<>();
        page.setList(list);
        page.setTotal(total);
        page.setHasMore(hasMore);
        return page;
    }
}
