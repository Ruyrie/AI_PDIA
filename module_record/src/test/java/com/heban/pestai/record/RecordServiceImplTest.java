package com.heban.pestai.record;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.heban.pestai.common.service.IRecordService;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.network.ApiException;
import com.heban.pestai.network.ApiResponse;
import com.heban.pestai.network.PageVO;
import com.heban.pestai.record.data.RecordApi;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.plugins.RxJavaPlugins;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class RecordServiceImplTest {

    private final FakeApi api = new FakeApi();
    private RecordServiceImpl service;

    @Before
    public void setUp() {
        RxJavaPlugins.setIoSchedulerHandler(s -> Schedulers.trampoline());
        service = new RecordServiceImpl(api);
    }

    @After
    public void tearDown() {
        RxJavaPlugins.reset();
    }

    @Test
    public void duplicateListenerIsCalledOnce() {
        AtomicInteger count = new AtomicInteger();
        IRecordService.OnRecordChangedListener listener = count::incrementAndGet;
        service.addOnChangedListener(listener);
        service.addOnChangedListener(listener);
        service.notifyChanged();
        assertEquals(1, count.get());
    }

    @Test
    public void removedListenerIsNotCalled() {
        AtomicInteger count = new AtomicInteger();
        IRecordService.OnRecordChangedListener listener = count::incrementAndGet;
        service.addOnChangedListener(listener);
        service.removeOnChangedListener(listener);
        service.notifyChanged();
        assertEquals(0, count.get());
    }

    @Test
    public void listenerMayRemoveItselfDuringCallback() {
        AtomicInteger count = new AtomicInteger();
        IRecordService.OnRecordChangedListener[] holder = new IRecordService.OnRecordChangedListener[1];
        holder[0] = () -> {
            count.incrementAndGet();
            service.removeOnChangedListener(holder[0]);
        };
        service.addOnChangedListener(holder[0]);
        service.addOnChangedListener(count::incrementAndGet);
        service.notifyChanged();
        service.notifyChanged();
        assertEquals(3, count.get());
    }

    @Test
    public void recentUnwrapsList() {
        api.recent = Single.just(new ApiResponse<>(0, "ok", TestRecords.sample().subList(0, 3)));
        List<RecordItemVO> list = service.recent(3).blockingGet();
        assertEquals(3, api.lastRecentSize);
        assertEquals(3, list.size());
        assertEquals(1024, list.get(0).getId());
    }

    @Test
    public void getUnwrapsSummary() {
        api.summary = Single.just(new ApiResponse<>(0, "ok", TestRecords.sample().get(1)));
        RecordItemVO vo = service.get(1023).blockingGet();
        assertEquals(1023, api.lastSummaryId);
        assertEquals("FAILED", vo.getStatus());
    }

    @Test
    public void getConvertsBusinessError() {
        api.summary = Single.just(new ApiResponse<>(ApiException.PARAM_ERROR, "记录不存在", null));
        try {
            service.get(1).blockingGet();
            fail("应抛出 ApiException");
        } catch (ApiException e) {
            assertEquals(ApiException.PARAM_ERROR, e.getCode());
            assertEquals("记录不存在", e.getMessage());
        }
    }

    @Test
    public void recentConvertsNetworkError() {
        api.recent = Single.error(new java.net.UnknownHostException("no network"));
        try {
            service.recent(3).blockingGet();
            fail("应抛出 ApiException");
        } catch (ApiException e) {
            assertEquals(ApiException.NETWORK, e.getCode());
            assertTrue(e.getMessage().contains("网络"));
        }
    }

    private static final class FakeApi implements RecordApi {
        Single<ApiResponse<List<RecordItemVO>>> recent = Single.never();
        Single<ApiResponse<RecordItemVO>> summary = Single.never();
        int lastRecentSize;
        long lastSummaryId;

        @Override
        public Single<ApiResponse<PageVO<RecordItemVO>>> list(String mode, int page, int size) {
            return Single.never();
        }

        @Override
        public Single<ApiResponse<Void>> delete(long id) {
            return Single.never();
        }

        @Override
        public Single<ApiResponse<List<RecordItemVO>>> recent(int size) {
            lastRecentSize = size;
            return recent;
        }

        @Override
        public Single<ApiResponse<RecordItemVO>> summary(long id) {
            lastSummaryId = id;
            return summary;
        }
    }
}
