package com.heban.pestai.ai.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.common.vo.SuggestionVO;
import com.heban.pestai.network.PageVO;

import java.util.List;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;

/**
 * AI 问答数据源（实现：{@link AiRepositoryImpl}）。返回的 Rx 流均为冷流，不指定线程，
 * 由 ViewModel 统一 subscribeOn(io) / observeOn(主线程)。抽成接口便于 ViewModel 单元测试使用假实现。
 */
public interface AiRepository {

    /** 游客或未登录（没有令牌）：不请求需要登录的接口 */
    boolean isGuest();

    /** GET ai/suggestions */
    @NonNull
    Single<List<SuggestionVO>> suggestions();

    /** POST ai/sessions；recordId 为空表示普通对话 */
    @NonNull
    Single<SessionVO> createSession(@Nullable Long recordId);

    /**
     * 压缩（ImageCompressor）并上传（POST files/image）本地图片，返回图片完整地址。
     * 读取或压缩失败时发出 {@link com.heban.pestai.base.util.ImageCompressor.ImageReadException}。
     */
    @NonNull
    Single<String> uploadImage(@NonNull String localUri);

    /**
     * POST ai/sessions/{id}/messages，返回带类型的片段流（思考片段 / 正文片段）；
     * BUFFER 背压，取消订阅即取消 HTTP 请求。
     */
    @NonNull
    Flowable<StreamChunk> sendMessage(long sessionId, @NonNull String content, @Nullable String imageUrl,
                                      boolean regenerate);

    /** GET ai/sessions：历史对话分页（Q03） */
    @NonNull
    Single<PageVO<SessionItemVO>> sessions(int page, int size);

    /** GET ai/sessions/{id}：打开历史对话（Q02） */
    @NonNull
    Single<SessionDetailVO> session(long sessionId);

    /** DELETE ai/sessions/{id}：删除历史对话（Q03） */
    @NonNull
    Completable deleteSession(long sessionId);

    /** 最近识别（IRecordService，由 module_record 实现） */
    @NonNull
    Single<List<RecordItemVO>> recentRecords(int size);

    /** 单条识别记录摘要（IRecordService，引用卡） */
    @NonNull
    Single<RecordItemVO> record(long id);
}
