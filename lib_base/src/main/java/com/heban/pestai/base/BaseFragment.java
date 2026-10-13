package com.heban.pestai.base;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

import com.heban.pestai.base.util.Snackbars;

/**
 * 所有 Fragment 的父类：统一获取 AppViewModelFactory，观察 toast 并以 Snackbar 显示。
 */
public abstract class BaseFragment extends Fragment {

    protected ViewModelProvider.Factory viewModelFactory() {
        return AppHost.from(requireContext()).getViewModelFactory();
    }

    /** Fragment 作用域的 ViewModel */
    protected <VM extends ViewModel> VM obtainViewModel(@NonNull Class<VM> modelClass) {
        return new ViewModelProvider(this, viewModelFactory()).get(modelClass);
    }

    /** Activity 作用域的 ViewModel（如 MainViewModel，与宿主 Activity 及其他 Fragment 共享） */
    protected <VM extends ViewModel> VM obtainActivityViewModel(@NonNull Class<VM> modelClass) {
        return new ViewModelProvider(requireActivity(), viewModelFactory()).get(modelClass);
    }

    protected <T> T deps(@NonNull Class<T> depsClass) {
        return AppHost.deps(requireContext(), depsClass);
    }

    /** 在 onViewCreated 中调用：观察一次性提示，以 Snackbar 显示 */
    protected void observeToast(@NonNull BaseViewModel viewModel) {
        viewModel.getToast().observe(getViewLifecycleOwner(), this::showSnackbar);
    }

    protected void showSnackbar(@Nullable CharSequence message) {
        if (getActivity() != null) {
            Snackbars.show(requireActivity(), message);
        }
    }
}
