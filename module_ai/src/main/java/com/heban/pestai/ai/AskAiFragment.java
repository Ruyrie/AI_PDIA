package com.heban.pestai.ai;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.heban.pestai.ai.chat.ChatActivity;
import com.heban.pestai.ai.chat.ChatArgs;
import com.heban.pestai.ai.compose.ComposerBar;
import com.heban.pestai.ai.databinding.FragmentAskAiBinding;
import com.heban.pestai.ai.history.ChatHistoryActivity;
import com.heban.pestai.ai.util.AiImagePicker;
import com.heban.pestai.ai.util.AiLoginDialog;
import com.heban.pestai.ai.util.AiMicPermission;
import com.heban.pestai.base.BaseFragment;
import com.heban.pestai.base.KeyboardTouchDelegate;
import com.heban.pestai.base.SnackbarAnchorProvider;

import java.util.List;

/**
 * Q01 AI 问答 · 新对话（MainActivity 第 2 个 Tab）。
 * 状态栏间距与软键盘由 MainActivity 统一处理（键盘弹出时隐藏底部导航并让出键盘高度），这里不再处理。
 * <ul>
 *     <li>页面只有三部分：顶部栏（标题 +「历史对话」打开 Q03）、在空白区居中的欢迎语、底部输入栏（{@link ComposerBar}）
 *     及其下方一行「内容由大模型生成，仅供参考」；</li>
 *     <li>欢迎语所在区域随键盘弹出、缩略图条出现而变矮，欢迎语始终在其中居中；放不下时隐藏，不与输入栏重叠；</li>
 *     <li>发送打开 Q02（模块内显式 Intent），对话在 Q02 进行。</li>
 * </ul>
 * 拍照与相册选图由 {@link AiImagePicker} 处理（申请 CAMERA 权限、TakePicture、照片选择器多选、进程回收后恢复）；
 * 文字 / 语音输入方式的切换与「按住 说话」由 ComposerBar 处理，麦克风权限 {@link AiMicPermission} 在 onCreate 中注册；
 * 回到本页（onResume）时同步在 Q02 中切换过的输入方式。
 */
public class AskAiFragment extends BaseFragment implements KeyboardTouchDelegate, SnackbarAnchorProvider {

    private FragmentAskAiBinding binding;
    private AskAiViewModel viewModel;
    private AiImagePicker imagePicker;
    private AiMicPermission micPermission;
    @Nullable
    private ComposerBar composer;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 必须在 STARTED 之前注册；系统照片选择器不需要存储权限。结果在 STARTED 后才回调，此时 ViewModel 已取得
        imagePicker = AiImagePicker.register(this, new AiImagePicker.Callback() {
            @Override
            public void onImagesPicked(@NonNull List<String> uris) {
                if (viewModel != null) {
                    viewModel.onImagesPicked(uris);
                }
            }

            @Override
            public void onPickFailed(int message) {
                showSnackbar(getString(message));
            }
        });
        // 授权结果由 ComposerBar 交给 ViewModel（点按麦克风图标申请的授权后切到语音）
        micPermission = AiMicPermission.register(this);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAskAiBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        viewModel = obtainViewModel(AskAiViewModel.class);
        binding.setLifecycleOwner(getViewLifecycleOwner());
        binding.setVm(viewModel);
        observeToast(viewModel);
        composer = ComposerBar.setup(binding.askComposer, getViewLifecycleOwner(), Glide.with(this), viewModel,
                micPermission);

        // 游客：输入框不可获得焦点，点击弹出登录对话框
        EditText input = composer.getInput();
        input.setOnClickListener(v -> viewModel.onInputClick());
        viewModel.getGuest().observe(getViewLifecycleOwner(), guest -> {
            boolean editable = !Boolean.TRUE.equals(guest);
            input.setFocusable(editable);
            input.setFocusableInTouchMode(editable);
            input.setCursorVisible(editable);
        });
        viewModel.getEvent().observe(getViewLifecycleOwner(), this::handleEvent);

        // 欢迎语区域每次布局后（键盘弹出 / 收起、缩略图条出现 / 消失、多行输入）检查是否放得下
        binding.askWelcomeArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> fitWelcome());
    }

    @Override
    public void onResume() {
        super.onResume();
        // MainActivity 中隐藏的 Tab 停在 STARTED，onResume 只在可见时回调：登录后回来输入框可以输入，
        // 输入方式同步为记住的方式
        viewModel.onResume();
    }

    @Override
    public void onDestroyView() {
        if (composer != null) {
            composer.release();
            composer = null;
        }
        super.onDestroyView();
        binding = null;
    }

    /**
     * 欢迎语在空白区居中；区域比欢迎语矮（小屏幕弹出键盘、又有待发送图片）时改为不可见（INVISIBLE 不触发重新布局），
     * 避免居中后上下超出、与顶部栏或输入栏重叠。
     */
    private void fitWelcome() {
        if (binding == null) {
            return;
        }
        View area = binding.askWelcomeArea;
        View welcome = binding.askWelcome;
        boolean fits = area.getHeight() >= welcome.getMeasuredHeight();
        int visibility = fits ? View.VISIBLE : View.INVISIBLE;
        if (welcome.getVisibility() != visibility) {
            welcome.setVisibility(visibility);
        }
    }

    /**
     * 由 MainActivity 询问：按在输入栏（待发送图片缩略图条、胶囊及其中的按钮）上时不按「点击输入框外收起键盘」处理，
     * 避免键盘先收起再弹出造成闪动、底部导航短暂出现。
     */
    @Override
    public boolean shouldHideKeyboardOnTouch(@NonNull MotionEvent ev) {
        return composer == null || !composer.contains(ev);
    }

    /** 由 MainActivity 询问：本页的提示显示在输入栏上方，不遮住输入框与「按住 说话」 */
    @Nullable
    @Override
    public View getSnackbarAnchor() {
        return composer == null ? null : composer.getRoot();
    }

    // ==================== 事件 ====================

    private void handleEvent(@Nullable AskAiViewModel.Event event) {
        if (event == null || binding == null) {
            return;
        }
        switch (event.type) {
            case AskAiViewModel.Event.NEED_LOGIN:
                AiLoginDialog.show(requireContext());
                break;
            case AskAiViewModel.Event.TAKE_PHOTO:
                imagePicker.takePhoto();
                break;
            case AskAiViewModel.Event.PICK_IMAGE:
                imagePicker.pickFromAlbum(viewModel.remainingImages());
                break;
            case AskAiViewModel.Event.OPEN_CHAT:
                if (event.args != null) {
                    openChat(event.args);
                }
                break;
            case AskAiViewModel.Event.OPEN_HISTORY:
                hideKeyboard();
                startActivity(new Intent(requireContext(), ChatHistoryActivity.class));
                break;
            case AskAiViewModel.Event.FOCUS_INPUT:
                // 选好照片后：输入框获得焦点并弹出键盘（语音输入时输入框隐藏，不处理）
                if (composer != null) {
                    composer.focusInput();
                }
                break;
            default:
                break;
        }
    }

    private void openChat(@NonNull ChatArgs args) {
        hideKeyboard();
        startActivity(ChatActivity.intent(requireContext(), args));
    }

    private void hideKeyboard() {
        if (composer != null) {
            composer.hideKeyboard();
        }
    }
}
