package com.heban.pestai.mine.testutil;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.heban.pestai.common.service.IUserService;
import com.heban.pestai.common.vo.RecordItemVO;
import com.heban.pestai.common.vo.RecoveryCodeVO;
import com.heban.pestai.common.vo.UserStatsVO;
import com.heban.pestai.common.vo.UserVO;
import com.heban.pestai.mine.data.CacheRepository;
import com.heban.pestai.mine.data.MineRepository;
import com.heban.pestai.mine.data.RecoveryReminderStore;
import com.heban.pestai.mine.data.SettingsRepository;

import java.util.HashMap;
import java.util.Map;

import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Single;

/**
 * ViewModel 测试用的假 Repository / 服务与示例数据（与原型一致：老李种菜、36 / 12 / 18、本月 9 次）。
 */
public final class Fakes {

    private Fakes() {
    }

    @NonNull
    public static UserVO sampleUser() {
        UserVO user = new UserVO();
        user.setId(10001);
        user.setPhone("13800005678");
        user.setNickname("老李种菜");
        user.setRegion("江苏省 南京市 江宁区");
        user.setIdentity("种植户");
        user.setCrops("番茄,黄瓜,小麦");
        return user;
    }

    @NonNull
    public static UserStatsVO sampleStats(@Nullable RecordItemVO lastRecord) {
        UserStatsVO stats = new UserStatsVO();
        stats.setTotal(36);
        stats.setPestKinds(12);
        stats.setAiCount(18);
        stats.setMonthCount(9);
        stats.setLastRecord(lastRecord);
        return stats;
    }

    /** 假 IUserService：登录态与当前用户由测试直接设置 */
    public static class FakeUserService implements IUserService {
        public boolean loggedIn;
        public UserVO user;
        public int logoutCalls;
        public final MutableLiveData<UserVO> changes = new MutableLiveData<>();

        @Nullable
        @Override
        public UserVO currentUser() {
            return loggedIn ? user : null;
        }

        @Override
        public boolean isLoggedIn() {
            return loggedIn;
        }

        @NonNull
        @Override
        public LiveData<UserVO> userChanges() {
            return changes;
        }

        @Override
        public void logout() {
            logoutCalls++;
            loggedIn = false;
            user = null;
        }
    }

    /** 假 MineRepository：返回测试设置的结果并统计调用次数 */
    public static class FakeMineRepository extends MineRepository {
        public Single<UserVO> meResult = Single.never();
        public Single<UserStatsVO> statsResult = Single.never();
        public Completable logoutResult = Completable.complete();
        public Single<RecoveryCodeVO> recoveryCodeResult = Single.never();
        public int meCalls;
        public int statsCalls;
        public int logoutCalls;
        public int recoveryCodeCalls;

        public FakeMineRepository() {
            super(null, null);
        }

        @NonNull
        @Override
        public Single<UserVO> me() {
            meCalls++;
            return meResult;
        }

        @NonNull
        @Override
        public Single<UserStatsVO> stats() {
            statsCalls++;
            return statsResult;
        }

        @NonNull
        @Override
        public Completable logout() {
            logoutCalls++;
            return logoutResult;
        }

        @NonNull
        @Override
        public Single<RecoveryCodeVO> recoveryCode() {
            recoveryCodeCalls++;
            return recoveryCodeResult;
        }
    }

    public static class FakeCacheRepository extends CacheRepository {
        public Single<Long> sizeResult = Single.just(0L);
        public Completable clearResult = Completable.complete();
        public int clearCalls;

        public FakeCacheRepository() {
            super(null);
        }

        @NonNull
        @Override
        public Single<Long> diskCacheSize() {
            return sizeResult;
        }

        @NonNull
        @Override
        public Completable clear() {
            clearCalls++;
            return clearResult;
        }
    }

    /** 假提醒关闭记录：内存 Map，按用户 ID 记录关闭时刻 */
    public static class FakeReminderStore extends RecoveryReminderStore {
        public final Map<Long, Long> dismissed = new HashMap<>();

        public FakeReminderStore() {
            super(null);
        }

        @Override
        public long dismissedAt(long userId) {
            Long value = dismissed.get(userId);
            return value == null ? 0L : value;
        }

        @Override
        public void setDismissedAt(long userId, long millis) {
            dismissed.put(userId, millis);
        }
    }

    /** GET user/recovery-code 的结果 */
    @NonNull
    public static RecoveryCodeVO recoveryStatus(boolean generated) {
        RecoveryCodeVO vo = new RecoveryCodeVO();
        vo.setGenerated(generated);
        vo.setVerifyRequired(true);
        return vo;
    }

    public static class FakeSettingsRepository extends SettingsRepository {
        public boolean saveToAlbum;
        public int writes;

        public FakeSettingsRepository() {
            super(null);
        }

        @Override
        public boolean isSaveToAlbum() {
            return saveToAlbum;
        }

        @Override
        public void setSaveToAlbum(boolean save) {
            writes++;
            saveToAlbum = save;
        }
    }
}
