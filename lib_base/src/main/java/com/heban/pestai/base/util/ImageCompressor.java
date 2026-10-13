package com.heban.pestai.base.util;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * 上传前压缩照片（模块05 识别照片、模块08 聊天图片、模块02 头像共用）。
 * 长边不超过 1280px、JPEG 质量 80、不超过 2MB（头像 1MB），按 EXIF 方向摆正。
 * 耗时操作，必须在 io 线程调用。
 */
@Singleton
public class ImageCompressor {

    public static final int MAX_EDGE = 1280;
    public static final int QUALITY = 80;
    public static final int MIN_QUALITY = 50;
    public static final long MAX_BYTES = 2L * 1024 * 1024;
    public static final long AVATAR_MAX_BYTES = 1024L * 1024;

    private final Context context;

    @Inject
    public ImageCompressor(@NonNull Context context) {
        this.context = context.getApplicationContext();
    }

    /** 使用 Application 的 ContentResolver，按默认上限 2MB 压缩 */
    @NonNull
    public CompressResult compress(@NonNull Uri source, @NonNull File outDir) throws IOException {
        return compress(context.getContentResolver(), source, outDir, MAX_BYTES);
    }

    @NonNull
    public CompressResult compress(@NonNull ContentResolver resolver, @NonNull Uri source, @NonNull File outDir)
            throws IOException {
        return compress(resolver, source, outDir, MAX_BYTES);
    }

    @NonNull
    public CompressResult compress(@NonNull ContentResolver resolver, @NonNull Uri source, @NonNull File outDir,
                                   long maxBytes) throws IOException {
        long originalSize = querySize(resolver, source);

        // 1. 只读尺寸
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(resolver, source)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new ImageReadException("无法读取图片：" + source);
        }

        // 2. 2 的幂次采样率
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longEdge / (sample * 2) >= MAX_EDGE) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap sampled;
        try (InputStream in = open(resolver, source)) {
            sampled = BitmapFactory.decodeStream(in, null, opts);
        }
        if (sampled == null) {
            throw new ImageReadException("图片解码失败：" + source);
        }

        // 3. 拍摄方向
        int degrees = readRotation(resolver, source);

        // 4. 缩放 + 旋转 + JPEG 输出（超限时逐级降质，质量 50 仍超限则长边再缩小为 0.8 倍）
        float targetEdge = MAX_EDGE;
        byte[] jpeg;
        while (true) {
            Bitmap scaled = transform(sampled, targetEdge, degrees);
            jpeg = encode(scaled, maxBytes);
            if (scaled != sampled) {
                scaled.recycle();
            }
            if (jpeg.length <= maxBytes || targetEdge < 320) {
                break;
            }
            targetEdge *= 0.8f;
        }
        sampled.recycle();

        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IOException("无法创建目录：" + outDir);
        }
        File out = new File(outDir, "upload_" + System.currentTimeMillis() + ".jpg");
        try (FileOutputStream fos = new FileOutputStream(out)) {
            fos.write(jpeg);
        }
        if (originalSize <= 0) {
            originalSize = out.length();
        }
        return new CompressResult(out, originalSize, out.length());
    }

    @NonNull
    private static InputStream open(ContentResolver resolver, Uri uri) throws IOException {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) {
            throw new ImageReadException("无法打开图片：" + uri);
        }
        return in;
    }

    private static long querySize(ContentResolver resolver, Uri uri) {
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            return new File(uri.getPath()).length();
        }
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0)) {
                long size = c.getLong(0);
                if (size > 0) {
                    return size;
                }
            }
        } catch (Exception ignored) {
            // 部分来源不支持查询，退回文件描述符
        }
        try (AssetFileDescriptor afd = resolver.openAssetFileDescriptor(uri, "r")) {
            return afd != null ? afd.getLength() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static int readRotation(ContentResolver resolver, Uri uri) {
        try (InputStream in = resolver.openInputStream(uri)) {
            if (in == null) {
                return 0;
            }
            int orientation = new ExifInterface(in).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private static Bitmap transform(Bitmap src, float targetEdge, int degrees) {
        float scale = Math.min(1f, targetEdge / Math.max(src.getWidth(), src.getHeight()));
        if (scale >= 1f && degrees == 0) {
            return src;
        }
        Matrix matrix = new Matrix();
        matrix.postScale(scale, scale);
        matrix.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
    }

    private static byte[] encode(Bitmap bitmap, long maxBytes) {
        int quality = QUALITY;
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        while (true) {
            bos.reset();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bos);
            if (bos.size() <= maxBytes || quality <= MIN_QUALITY) {
                return bos.toByteArray();
            }
            quality -= 10;
        }
    }

    /** 小于 1MB 显示整数 KB，否则显示保留 1 位小数的 MB，如「320 KB」「1.2 MB」 */
    @NonNull
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "--";
        }
        if (bytes < 1024L * 1024) {
            return Math.round(bytes / 1024f) + " KB";
        }
        return String.format(Locale.CHINA, "%.1f MB", bytes / 1024f / 1024f);
    }

    /** 压缩结果：压缩后文件、原始字节数、压缩后字节数 */
    public static final class CompressResult {
        public final File file;
        public final long originalSize;
        public final long compressedSize;

        public CompressResult(@NonNull File file, long originalSize, long compressedSize) {
            this.file = file;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
        }
    }

    /** 文件损坏或不是图片 */
    public static class ImageReadException extends IOException {
        public ImageReadException(String message) {
            super(message);
        }
    }
}
