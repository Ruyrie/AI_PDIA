package com.heban.pestai.recognize.di;

import androidx.lifecycle.ViewModel;

import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.recognize.data.RecognizeApi;
import com.heban.pestai.recognize.ui.analyzing.AnalyzingViewModel;
import com.heban.pestai.recognize.ui.error.ScanErrorViewModel;
import com.heban.pestai.recognize.ui.result.ResultViewModel;
import com.heban.pestai.recognize.ui.scan.ScanViewModel;
import com.heban.pestai.recognize.util.ResourceTextSource;
import com.heban.pestai.recognize.util.TextSource;

import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import retrofit2.Retrofit;

/**
 * module_recognize 的 Dagger Module（S01、S02、R01、R02（模块05、06）），由壳工程 AppComponent 组合。
 * RecognizeRepository、ResourceTextSource 使用 {@code @Singleton} + {@code @Inject} 构造方法，无需在这里声明。
 */
@Module
public abstract class RecognizeModule {

    @Provides
    @Singleton
    static RecognizeApi provideRecognizeApi(Retrofit retrofit) {
        return retrofit.create(RecognizeApi.class);
    }

    @Binds
    abstract TextSource bindRecognizeTextSource(ResourceTextSource textSource);

    @Binds
    @IntoMap
    @ViewModelKey(ScanViewModel.class)
    abstract ViewModel bindScanViewModel(ScanViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(AnalyzingViewModel.class)
    abstract ViewModel bindAnalyzingViewModel(AnalyzingViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ResultViewModel.class)
    abstract ViewModel bindResultViewModel(ResultViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ScanErrorViewModel.class)
    abstract ViewModel bindScanErrorViewModel(ScanErrorViewModel viewModel);
}
