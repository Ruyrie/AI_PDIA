package com.heban.pestai.ai.di;

import androidx.lifecycle.ViewModel;

import com.google.gson.Gson;
import com.heban.pestai.ai.AskAiViewModel;
import com.heban.pestai.ai.chat.ChatViewModel;
import com.heban.pestai.ai.data.AiApi;
import com.heban.pestai.ai.data.AiRepository;
import com.heban.pestai.ai.data.AiRepositoryImpl;
import com.heban.pestai.ai.data.AiStreamApi;
import com.heban.pestai.ai.history.ChatHistoryViewModel;
import com.heban.pestai.base.di.ViewModelKey;
import com.heban.pestai.network.di.NetworkModule;

import javax.inject.Named;
import javax.inject.Singleton;

import dagger.Binds;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoMap;
import okhttp3.OkHttpClient;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * module_ai 的 Dagger Module（Q01、Q02（模块08）、Q03 历史对话），由壳工程 AppComponent 组合。
 * <ul>
 *     <li>AiApi：默认 Retrofit（读取超时 30 秒）；</li>
 *     <li>AiStreamApi：用 {@code @Named(NetworkModule.STREAM)} 的 OkHttpClient（读取超时 120 秒、日志只打印请求头）
 *     单独构建 Retrofit 创建，误用默认 Retrofit 时模型思考超过 30 秒就会读取超时；</li>
 *     <li>AiRepository → AiRepositoryImpl；AskAiViewModel、ChatViewModel、ChatHistoryViewModel 注册到 ViewModel 工厂。</li>
 * </ul>
 */
@Module
public abstract class AiModule {

    @Provides
    @Singleton
    static AiApi provideAiApi(Retrofit retrofit) {
        return retrofit.create(AiApi.class);
    }

    @Provides
    @Singleton
    static AiStreamApi provideAiStreamApi(@Named(NetworkModule.STREAM) OkHttpClient streamClient, Gson gson,
                                          @Named(NetworkModule.BASE_URL) String baseUrl) {
        return new Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(streamClient)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(AiStreamApi.class);
    }

    @Binds
    abstract AiRepository bindAiRepository(AiRepositoryImpl repository);

    @Binds
    @IntoMap
    @ViewModelKey(AskAiViewModel.class)
    abstract ViewModel bindAskAiViewModel(AskAiViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ChatViewModel.class)
    abstract ViewModel bindChatViewModel(ChatViewModel viewModel);

    @Binds
    @IntoMap
    @ViewModelKey(ChatHistoryViewModel.class)
    abstract ViewModel bindChatHistoryViewModel(ChatHistoryViewModel viewModel);
}
