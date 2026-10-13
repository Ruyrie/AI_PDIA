package com.heban.pestai.recognize.data;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.heban.pestai.base.util.ImageCompressor;
import com.heban.pestai.common.session.UserSession;
import com.heban.pestai.common.vo.RecognitionVO;
import com.heban.pestai.network.RxResult;
import com.heban.pestai.recognize.R;
import com.heban.pestai.recognize.model.RecognizeMode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;

/**
 * 模块05、06 共用的数据入口（Dagger 单例）：压缩、上传识别、识别详情、重新识别、保存原图到相册、临时文件清理。
 * 网络请求都在 io 线程执行，结果经 {@link RxResult#transform()} 取出 RecognitionVO，失败时为 ApiException。
 */
@Singleton
public class RecognizeRepository {

    private static final String TAG = "RecognizeRepository";

    /** 拍照原图与压缩图的目录：cacheDir/scan/ */
    public static final String SCAN_DIR = "scan";
    /** 进入 S01 时删除 1 天前的临时照片 */
    private static final long KEEP_MS = TimeUnit.DAYS.toMillis(1);
    private static final MediaType JPEG = MediaType.get("image/jpeg");
    private static final MediaType TEXT = MediaType.get("text/plain");

    private final RecognizeApi api;
    private final UserSession session;
    private final ImageCompressor compressor;
    private final Context context;

    @Inject
    public RecognizeRepository(RecognizeApi api, UserSession session, ImageCompressor compressor, Context context) {
        this.api = api;
        this.session = session;
        this.compressor = compressor;
        this.context = context == null ? null : context.getApplicationContext();
    }

    @NonNull
    public static File scanDir(@NonNull Context context) {
        return new File(context.getCacheDir(), SCAN_DIR);
    }

    // ---------- 模块05：压缩与上传识别 ----------

    /** 压缩照片（长边 ≤ 1280px、JPEG 80、≤ 2MB），输出到 cacheDir/scan/upload_时间戳.jpg；必须在 io 线程调用 */
    @NonNull
    public ImageCompressor.CompressResult compress(@NonNull Uri source) throws IOException {
        return compressor.compress(context.getContentResolver(), source, scanDir(context));
    }

    /**
     * POST recognitions（multipart：image + mode）。
     *
     * @param listener 上传百分比回调（OkHttp 写线程）
     */
    @NonNull
    public Single<RecognitionVO> recognize(@NonNull File file, @NonNull RecognizeMode mode,
                                           @NonNull ProgressRequestBody.Listener listener) {
        return Single.defer(() -> {
                    RequestBody image = new ProgressRequestBody(RequestBody.create(file, JPEG), listener);
                    MultipartBody.Part part = MultipartBody.Part.createFormData("image", file.getName(), image);
                    RequestBody modeBody = RequestBody.create(mode.value(), TEXT);
                    return api.recognize(part, modeBody);
                })
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    // ---------- 模块06：详情与重新识别 ----------

    /** GET recognitions/{id} */
    @NonNull
    public Single<RecognitionVO> getDetail(long id) {
        return Single.defer(() -> api.getDetail(id))
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    /** POST recognitions/{id}/retry：用服务器上的原照片重新识别 */
    @NonNull
    public Single<RecognitionVO> retry(long id) {
        return Single.defer(() -> api.retry(id))
                .compose(RxResult.transform())
                .subscribeOn(Schedulers.io());
    }

    // ---------- 本机文件 ----------

    /** 设置页「识别照片保存到相册」（模块09 写入，默认 false） */
    public boolean isSaveToAlbum() {
        return session.isSaveToAlbum();
    }

    /**
     * 把相机拍下的原图复制到系统相册 Pictures/病虫害识别助手（MediaStore，minSdk 31 无需存储权限）。
     * 在 io 线程执行；失败时删除未完成的媒体记录并抛出异常，由调用方只记录日志。
     */
    @NonNull
    public Completable saveToAlbum(@NonNull String sourceUri) {
        return Completable.fromAction(() -> copyToAlbum(Uri.parse(sourceUri)))
                .subscribeOn(Schedulers.io());
    }

    private void copyToAlbum(@NonNull Uri source) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String time = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date());
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "PEST_" + time + ".jpg");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + File.separator + context.getString(R.string.scan_album_folder));
        values.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri target = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (target == null) {
            throw new IOException("MediaStore insert 失败");
        }
        try {
            try (InputStream in = resolver.openInputStream(source);
                 OutputStream out = resolver.openOutputStream(target)) {
                if (in == null || out == null) {
                    throw new IOException("无法打开原图或相册文件");
                }
                byte[] buffer = new byte[8 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(target, done, null, null);
        } catch (IOException | RuntimeException e) {
            resolver.delete(target, null, null);
            throw e;
        }
    }

    /** 删除 cacheDir/scan/ 中 1 天前的拍照原图与压缩图（io 线程） */
    @NonNull
    public Completable cleanOldFiles() {
        return Completable.fromAction(() -> {
            File[] files = scanDir(context).listFiles();
            if (files == null) {
                return;
            }
            long expire = System.currentTimeMillis() - KEEP_MS;
            for (File f : files) {
                if (f.isFile() && f.lastModified() < expire && !f.delete()) {
                    Log.w(TAG, "删除临时照片失败：" + f);
                }
            }
        }).subscribeOn(Schedulers.io());
    }

    /** 本机照片是否仍可读取（R02 无记录时重新上传前确认，io 线程） */
    @NonNull
    public Single<Boolean> isReadable(@Nullable String uri) {
        return Single.fromCallable(() -> {
            if (uri == null || uri.isEmpty()) {
                return false;
            }
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(Uri.parse(uri), "r")) {
                return pfd != null;
            } catch (Exception e) {
                return false;
            }
        }).subscribeOn(Schedulers.io());
    }

    /** 删除压缩后的临时文件（识别结束或取消后） */
    public static void deleteQuietly(@Nullable File file) {
        if (file != null && file.exists() && !file.delete()) {
            Log.w(TAG, "删除压缩文件失败：" + file);
        }
    }
}
