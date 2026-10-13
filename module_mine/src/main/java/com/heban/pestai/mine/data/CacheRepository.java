package com.heban.pestai.mine.data;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.heban.pestai.mine.util.FileSizes;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Glide 图片缓存的统计与清理（模块09 技术准备文档 2.7.4）。
 * 全应用图片都由 Glide 加载，这里统计和清理的就是全部图片缓存。
 * Glide 要求 clearDiskCache() 在后台线程、clearMemory() 在主线程调用，否则直接抛出异常。
 */
@Singleton
public class CacheRepository {

    /** 注入的是 Application Context（壳工程 AppModule 绑定） */
    private final Context context;

    @Inject
    public CacheRepository(Context context) {
        this.context = context;
    }

    /** 磁盘缓存目录（cacheDir/image_manager_disk_cache）的字节数；目录不可用时为 0。io 线程计算 */
    @NonNull
    public Single<Long> diskCacheSize() {
        return Single.fromCallable(() -> FileSizes.sizeOf(Glide.getPhotoCacheDir(context)))
                .subscribeOn(Schedulers.io());
    }

    /** io 线程 clearDiskCache() → 主线程 clearMemory()；完成信号在主线程发出 */
    @NonNull
    public Completable clear() {
        return Completable.fromAction(() -> Glide.get(context).clearDiskCache())
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .doOnComplete(() -> Glide.get(context).clearMemory());
    }
}
