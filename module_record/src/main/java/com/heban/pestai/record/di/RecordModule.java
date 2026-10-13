package com.heban.pestai.record.di;

import androidx.lifecycle.ViewModel;

import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.record.RecordServiceImpl;
import com.heban.pestai.record.data.RecordApi;
import com.heban.pestai.record.ui.HistoryViewModel;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import retrofit2.Retrofit;

/**
 * module_record 的 Dagger Module（R03、R04（模块07）），由壳工程 AppComponent 组合。
 * <ul>
 *     <li>RecordApi：由全局 Retrofit 创建，RecordRepository 与 RecordServiceImpl 共用。</li>
 *     <li>RecordRepository 使用 {@code @Singleton} + {@code @Inject} 构造方法，无需在这里声明。</li>
 *     <li>HistoryViewModel 注册到 AppViewModelFactory。</li>
 * </ul>
 */
@Module
public abstract class RecordModule {

    @Provides
    @Singleton
    static RecordApi provideRecordApi(Retrofit retrofit) {
        return retrofit.create(RecordApi.class);
    }

    /** IRecordService 由本组件实现，PestApp 启动时注册到 ServiceHub */
    @Binds
    abstract IRecordService bindRecordService(RecordServiceImpl impl);

    @Binds
    @IntoMap
    @ViewModelKey(HistoryViewModel.class)
    abstract ViewModel bindHistoryViewModel(HistoryViewModel viewModel);
}
