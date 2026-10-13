package com.heban.pestai.ai;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.data.StreamChunk;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.common.vo.SuggestionVO;
import com.heban.pestai.network.PageVO;

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
        @Nullable
        public final String imageUrl;
        public final boolean regenerate;

        SendCall(long sessionId, String content, @Nullable String imageUrl, boolean regenerate) {
            this.sessionId = sessionId;
            this.content = content;
            this.imageUrl = imageUrl;
            this.regenerate = regenerate;
        }
    }

    public interface Answer {
        Flowable<StreamChunk> answer(SendCall call);
    }

    public interface Pages {
        Single<PageVO<SessionItemVO>> page(int page, int size);
    }

    public boolean guest;

    public Single<List<SuggestionVO>> suggestionsResult = Single.just(Collections.emptyList());
    public int suggestionsCalls;

    public long nextSessionId = 512;
    public final List<Long> createSessionRecordIds = new ArrayList<>();
    public Single<SessionVO> createSessionResult;

    public Single<String> uploadResult = Single.just("http://10.0.2.2:8080/files/chat/20261013/8f2c.jpg");
    public final List<String> uploadCalls = new ArrayList<>();

    public final List<SendCall> sendCalls = new ArrayList<>();
    public Answer answer = call -> contents("从照片看，", "叶背霉层比上一张更密。");

    public Single<List<RecordItemVO>> recentResult = Single.just(Collections.emptyList());
    public int recentCalls;
    public final List<Integer> recentSizes = new ArrayList<>();
    public Single<RecordItemVO> recordResult = Single.error(new IllegalStateException("not found"));
    public final List<Long> recordCalls = new ArrayList<>();

    public Pages pages = (page, size) -> Single.just(page(Collections.emptyList(), 0, false));
    public final List<Integer> pageCalls = new ArrayList<>();
    public Single<SessionDetailVO> sessionResult = Single.error(new IllegalStateException("not set"));
    public final List<Long> sessionCalls = new ArrayList<>();
    public Completable deleteResult = Completable.complete();
    public final List<Long> deleteCalls = new ArrayList<>();

    @Override
    public boolean isGuest() {
        return guest;
    }

    @NonNull
    @Override
    public Single<List<SuggestionVO>> suggestions() {
        suggestionsCalls++;
        return suggestionsResult;
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
        return uploadResult;
    }

    @NonNull
    @Override
    public Flowable<StreamChunk> sendMessage(long sessionId, @NonNull String content, @Nullable String imageUrl,
                                             boolean regenerate) {
        SendCall call = new SendCall(sessionId, content, imageUrl, regenerate);
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
    public Single<List<RecordItemVO>> recentRecords(int size) {
        recentCalls++;
        recentSizes.add(size);
        return recentResult;
    }

    @NonNull
    @Override
    public Single<RecordItemVO> record(long id) {
        recordCalls.add(id);
        return recordResult;
    }

    // ==================== 构造测试数据 ====================

    /** 只有正文片段的回答 */
    public static Flowable<StreamChunk> contents(String... texts) {
        List<StreamChunk> list = new ArrayList<>();
        for (String t : texts) {
            list.add(StreamChunk.content(t));
        }
        return Flowable.fromIterable(list);
    }

    public static SuggestionVO suggestion(long id, String text) {
        SuggestionVO vo = new SuggestionVO();
        vo.setId(id);
        vo.setText(text);
        return vo;
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
