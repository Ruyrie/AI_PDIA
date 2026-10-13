package com.heban.pestai.mine.ui.agreement;

import static com.heban.pestai.mine.testutil.LiveDataTestUtil.value;
import static org.junit.Assert.assertEquals;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;

import com.heban.pestai.base.UiState;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.mine.data.AgreementRepository;
import com.heban.pestai.mine.testutil.RxTestRule;

import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import io.reactivex.rxjava3.core.Single;

public class AgreementViewModelTest {

    @Rule
    public InstantTaskExecutorRule instantRule = new InstantTaskExecutorRule();
    @Rule
    public RxTestRule rxRule = new RxTestRule();

    @Test
    public void loadsRequestedType() {
        FakeAgreementRepository repo = new FakeAgreementRepository(Single.just("<p>隐私</p>"));
        AgreementViewModel vm = new AgreementViewModel(repo);
        vm.init(RouterPath.AGREEMENT_PRIVACY);

        assertEquals(RouterPath.AGREEMENT_PRIVACY, vm.getType());
        assertEquals(UiState.SUCCESS, value(vm.getUiState()));
        assertEquals("<p>隐私</p>", value(vm.getHtml()));
        assertEquals(RouterPath.AGREEMENT_PRIVACY, repo.requested.get(0));
    }

    @Test
    public void missingOrUnknownTypeFallsBackToUserAgreement() {
        AgreementViewModel vm = new AgreementViewModel(new FakeAgreementRepository(Single.just("")));
        vm.init(null);
        assertEquals(RouterPath.AGREEMENT_USER, vm.getType());

        vm = new AgreementViewModel(new FakeAgreementRepository(Single.just("")));
        vm.init("OTHER");
        assertEquals(RouterPath.AGREEMENT_USER, vm.getType());
    }

    @Test
    public void initTwiceLoadsOnce() {
        FakeAgreementRepository repo = new FakeAgreementRepository(Single.just("<p>协议</p>"));
        AgreementViewModel vm = new AgreementViewModel(repo);
        vm.init(RouterPath.AGREEMENT_USER);
        vm.init(RouterPath.AGREEMENT_PRIVACY);
        assertEquals(1, repo.requested.size());
        assertEquals(RouterPath.AGREEMENT_USER, vm.getType());
    }

    @Test
    public void readFailureShowsError() {
        AgreementViewModel vm = new AgreementViewModel(
                new FakeAgreementRepository(Single.error(new IOException("missing"))));
        vm.init(RouterPath.AGREEMENT_USER);
        assertEquals(UiState.ERROR, value(vm.getUiState()));
    }

    private static final class FakeAgreementRepository extends AgreementRepository {
        final List<String> requested = new ArrayList<>();
        private final Single<String> result;

        FakeAgreementRepository(Single<String> result) {
            super(null);
            this.result = result;
        }

        @NonNull
        @Override
        public Single<String> load(@Nullable String type) {
            requested.add(type);
            return result;
        }
    }
}
