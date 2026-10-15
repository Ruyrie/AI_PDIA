package com.heban.pestai.ai.data;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.service.ServiceHub;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.SessionDetailVO;
import com.heban.pestai.common.vo.SessionItemVO;
import com.heban.pestai.common.vo.SessionVO;
import com.heban.pestai.common.vo.UploadVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;
import com.heban.pestai.network.RxResult;

import java.io.File;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.BackpressureStrategy;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Flowable;
import io.reactivex.rxjava3.core.Single;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

/**
 * AI 问答 Repository：普通接口经 RxResult 解包为 VO；流式回答用 Flowable.create（BUFFER 背压）
 * 包装 OkHttp 同步请求并逐行解析为带类型的片段（思考 / 正文）；图片先用 lib_base 的 ImageCompressor 压缩再上传；
 * 语音输入的录音（WAV）原样以 multipart 上传识别。
 */
@Singleton
public class AiRepositoryImpl implements AiRepository {

    private static final String UPLOAD_DIR = "ai_upload";
    private static final MediaType JPEG = MediaType.get("image/jpeg");
    private static final MediaType WAV = MediaType.get("audio/wav");

    private final AiApi api;
    private final AiStreamApi streamApi;
    private final Gson gson;
    private final UserSession session;
    private final ImageCompressor compressor;
    private final Context context;

    @Inject
    public AiRepositoryImpl(AiApi api, AiStreamApi streamApi, Gson gson, UserSession session,
                            ImageCompressor compressor, Context context) {
        this.api = api;
        this.streamApi = streamApi;
        this.gson = gson;
        this.session = session;
        this.compressor = compressor;
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean isGuest() {
        return !session.isLoggedIn();
    }

    @NonNull
    @Override
    public Single<SessionVO> createSession(@Nullable Long recordId) {
        return api.createSession(new CreateSessionReq(recordId)).compose(RxResult.<SessionVO>transform());
    }

    @NonNull
    @Override
    public Single<String> uploadImage(@NonNull String localUri) {
        // 用 create + tryOnError：移除图片（取消上传）后才出现的压缩错误直接忽略，不会进入 RxJava 全局错误处理
        return Single.<File>create(emitter -> {
                    try {
                        emitter.onSuccess(compress(localUri));
                    } catch (Throwable t) {
                        emitter.tryOnError(t);
                    }
                })
                .flatMap(file -> {
                    RequestBody body = RequestBody.create(file, JPEG);
                    MultipartBody.Part part = MultipartBody.Part.createFormData("image", file.getName(), body);
                    return api.uploadImage(part)
                            .compose(RxResult.<UploadVO>transform())
                            .map(vo -> {
                                if (vo.getUrl() == null || vo.getUrl().isEmpty()) {
                                    throw ApiException.of(ApiException.SERVER_ERROR);
                                }
                                return vo.getUrl();
                            })
                            .doFinally(() -> deleteQuietly(file));
                });
    }

    /** 读取、解码、压缩失败统一转换为 ImageReadException（界面提示「图片读取失败，请换一张试试」） */
    @NonNull
    private File compress(@NonNull String localUri) throws ImageCompressor.ImageReadException {
        try {
            return compressor.compress(Uri.parse(localUri), new File(context.getCacheDir(), UPLOAD_DIR)).file;
        } catch (ImageCompressor.ImageReadException e) {
            throw e;
        } catch (Exception | OutOfMemoryError e) {
            ImageCompressor.ImageReadException error = new ImageCompressor.ImageReadException("图片读取失败：" + localUri);
            error.initCause(e);
            throw error;
        }
    }

    @NonNull
    @Override
    public Flowable<StreamChunk> sendMessage(long sessionId, @NonNull String content, @NonNull List<String> imageUrls,
                                             boolean regenerate) {
        SendMessageReq request = new SendMessageReq(content, imageUrls, regenerate);
        return Flowable.<StreamChunk>create(emitter -> {
            Call<ResponseBody> call = streamApi.sendMessage(sessionId, request);
            // dispose（停止生成 / 页面销毁）时取消请求，阻塞中的 readUtf8Line() 随之抛出 IOException
            emitter.setCancellable(call::cancel);
            Response<ResponseBody> response;
            try {
                response = call.execute();
            } catch (Throwable e) {
                emitter.tryOnError(e);
                return;
            }
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                if (body != null) {
                    body.close();
                }
                emitter.tryOnError(httpError(response));
                return;
            }
            try (ResponseBody stream = body) {
                StreamParser.parse(stream, gson, emitter);
            } catch (Throwable e) {
                // 断网、读取超时、主动取消；已取消时 tryOnError 直接忽略
                emitter.tryOnError(e);
            }
        }, BackpressureStrategy.BUFFER);
    }

    /** 非 2xx：错误体是 ApiResponse 时取其 code，否则 401 → 40101、429 → 42901、其他 → 50001 */
    @NonNull
    private ApiException httpError(@NonNull Response<?> response) {
        ResponseBody error = response.errorBody();
        if (error != null) {
            try (ResponseBody body = error) {
                ApiResponse<?> result = gson.fromJson(body.charStream(), ApiResponse.class);
                if (result != null && result.getCode() != ApiException.OK) {
                    return ApiException.of(result.getCode(), result.getMessage());
                }
            } catch (Exception ignored) {
                // 错误体不是 JSON
            }
        }
        if (response.code() == 401) {
            return ApiException.of(ApiException.UNAUTHORIZED);
        }
        if (response.code() == 429) {
            return ApiException.of(ApiException.TOO_FREQUENT);
        }
        return ApiException.of(ApiException.SERVER_ERROR);
    }

    @NonNull
    @Override
    public Single<PageVO<SessionItemVO>> sessions(int page, int size) {
        return api.sessions(page, size).compose(RxResult.<PageVO<SessionItemVO>>transform());
    }

    @NonNull
    @Override
    public Single<SessionDetailVO> session(long sessionId) {
        return api.session(sessionId).compose(RxResult.<SessionDetailVO>transform());
    }

    @NonNull
    @Override
    public Completable deleteSession(long sessionId) {
        return api.deleteSession(sessionId).to(RxResult.<Void>completable());
    }

    @NonNull
    @Override
    public Single<RecordItemVO> record(long id) {
        return Single.defer(() -> ServiceHub.get(IRecordService.class).get(id));
    }

    @NonNull
    @Override
    public Single<String> recognizeSpeech(@NonNull File wav) {
        return Single.defer(() -> {
            RequestBody body = RequestBody.create(wav, WAV);
            MultipartBody.Part part = MultipartBody.Part.createFormData("audio", wav.getName(), body);
            return api.recognizeSpeech(part).compose(RxResult.<AsrVO>transform());
        }).map(vo -> vo.getText() == null ? "" : vo.getText());
    }

    private static void deleteQuietly(@NonNull File file) {
        //noinspection ResultOfMethodCallIgnored 临时文件，删除失败由系统清理缓存目录
        file.delete();
    }
}
