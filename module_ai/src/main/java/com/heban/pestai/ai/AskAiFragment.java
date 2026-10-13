package com.heban.pestai.ai;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.os.BundleCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.material.chip.Chip;
import com.heban.pestai.ai.chat.ChatActivity;
import com.heban.pestai.ai.chat.ChatArgs;
import com.heban.pestai.ai.databinding.FragmentAskAiBinding;
import com.heban.pestai.ai.databinding.ItemAiSuggestionBinding;
import com.heban.pestai.ai.history.ChatHistoryActivity;
import com.heban.pestai.ai.util.AiFileProvider;
import com.heban.pestai.ai.util.AiLoginDialog;
import com.heban.pestai.ai.util.AiPressFeedback;
import com.heban.pestai.base.BaseFragment;
import com.heban.pestai.base.KeyboardTouchDelegate;
import com.heban.pestai.base.util.DimenUtils;
import com.heban.pestai.base.util.KeyboardUtils;
import com.heban.pestai.base.util.RxClick;
import com.heban.pestai.common.router.RouterPath;
import com.heban.pestai.common.vo.SuggestionVO;

import java.io.IOException;
import java.util.List;

/**
 * Q01 AI 问答 · 新对话（MainActivity 第 2 个 Tab）。
 * 状态栏间距与软键盘由 MainActivity 统一处理（键盘弹出时隐藏底部导航并让出键盘高度），这里不再处理。
 * <ul>
 *     <li>发送、点大家常问、点继续追问、选择要追问的识别记录都打开 Q02（模块内显式 Intent），对话在 Q02 进行；</li>
 *     <li>右上角「历史对话」打开 Q03；</li>
 *     <li>三张能力卡片：文字提问（键盘 + 提问模板）、带图提问（{@link ImageSourceSheet}：系统相机 / 照片选择器）、
 *     追问结果（{@link RecordPickerSheet}）；卡片有水波纹与按压缩放反馈。</li>
 * </ul>
 * 拍照用 ActivityResultContracts.TakePicture + {@link AiFileProvider}，启动系统相机前先申请 CAMERA 权限
 * （合并清单中声明了 CAMERA 权限时，未授权直接启动系统相机会抛出 SecurityException）。
 */
public class AskAiFragment extends BaseFragment implements KeyboardTouchDelegate {

    private static final String STATE_CAMERA_URI = "ai_camera_uri";
    private static final String TAG_IMAGE_SOURCE = "ai_image_source";
    private static final String TAG_RECORD_PICKER = "ai_record_picker";
    /** 键盘收起后等待这么久再隐藏模板行：点模板 Chip 时基础组件会先收起键盘，点击后马上重新弹出 */
    private static final long KEYBOARD_HIDE_DELAY_MS = 400;
    /** 点模板后这段时间内的「键盘收起」不隐藏模板行 */
    private static final long TEMPLATE_TOUCH_GRACE_MS = 800;

    private FragmentAskAiBinding binding;
    private AskAiViewModel viewModel;
    private ActivityResultLauncher<PickVisualMediaRequest> pickImage;
    private ActivityResultLauncher<String> cameraPermission;
    private ActivityResultLauncher<Uri> takePicture;
    /** 系统相机正在写入的文件（进程被回收后从 savedInstanceState 恢复） */
    @Nullable
    private Uri pendingCameraUri;
    private boolean imeVisible;
    private long lastTemplateTouch;
    private final Runnable keyboardHiddenCheck = this::checkKeyboardHidden;
    /** 等待窗口获得焦点后弹出键盘 */
    @Nullable
    private ViewTreeObserver.OnWindowFocusChangeListener focusWaiter;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            pendingCameraUri = BundleCompat.getParcelable(savedInstanceState, STATE_CAMERA_URI, Uri.class);
        }
        // 必须在 STARTED 之前注册；系统照片选择器不需要存储权限
        pickImage = registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
            if (uri != null && viewModel != null) {
                viewModel.onImagePicked(uri.toString());
            }
        });
        cameraPermission = registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
            if (Boolean.TRUE.equals(granted)) {
                launchCamera();
            } else {
                showSnackbar(getString(R.string.ai_camera_denied));
            }
        });
        takePicture = registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
            Uri uri = pendingCameraUri;
            pendingCameraUri = null;
            if (uri == null) {
                return;
            }
            if (Boolean.TRUE.equals(success) && viewModel != null) {
                viewModel.onImagePicked(uri.toString());
            } else {
                AiFileProvider.deleteQuietly(requireContext(), uri);
            }
        });
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

        // 游客：输入框不可获得焦点，点击弹出登录对话框
        binding.askInput.setOnClickListener(v -> viewModel.onInputClick());
        viewModel.getGuest().observe(getViewLifecycleOwner(), guest -> {
            boolean editable = !Boolean.TRUE.equals(guest);
            binding.askInput.setFocusable(editable);
            binding.askInput.setFocusableInTouchMode(editable);
            binding.askInput.setCursorVisible(editable);
        });
        viewModel.getSuggestions().observe(getViewLifecycleOwner(), this::renderSuggestions);
        viewModel.getEvent().observe(getViewLifecycleOwner(), this::handleEvent);

        AiPressFeedback.attach(binding.askCardText);
        AiPressFeedback.attach(binding.askCardImage);
        AiPressFeedback.attach(binding.askCardFollow);
        renderTemplates();
        watchKeyboard(view);

        viewModel.loadSuggestions();
    }

    @Override
    public void onResume() {
        super.onResume();
        // MainActivity 中隐藏的 Tab 停在 STARTED，onResume 只在可见时回调：识别完回来能看到最新记录
        viewModel.onResume();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pendingCameraUri != null) {
            outState.putParcelable(STATE_CAMERA_URI, pendingCameraUri);
        }
    }

    @Override
    public void onDestroyView() {
        if (binding != null) {
            removeFocusWaiter();
            binding.getRoot().removeCallbacks(keyboardHiddenCheck);
            ViewCompat.setOnApplyWindowInsetsListener(binding.getRoot(), null);
        }
        super.onDestroyView();
        binding = null;
    }

    // ==================== 大家常问与提问模板 ====================

    /** 只有 3 条，用 LinearLayout 填充，不使用 RecyclerView */
    private void renderSuggestions(@Nullable List<SuggestionVO> list) {
        if (binding == null) {
            return;
        }
        ViewGroup container = binding.askSuggestionList;
        container.removeAllViews();
        if (list == null) {
            return;
        }
        LayoutInflater inflater = getLayoutInflater();
        int gap = DimenUtils.dp(requireContext(), 8);
        for (int i = 0; i < list.size(); i++) {
            SuggestionVO suggestion = list.get(i);
            ItemAiSuggestionBinding row = ItemAiSuggestionBinding.inflate(inflater, container, false);
            row.aiSuggestionText.setText(suggestion.getText());
            if (i > 0) {
                ((ViewGroup.MarginLayoutParams) row.getRoot().getLayoutParams()).topMargin = gap;
            }
            // 防连点 500ms
            RxClick.throttle(row.getRoot(), v -> viewModel.askSuggestion(suggestion));
            container.addView(row.getRoot());
        }
    }

    /** 提问模板 Chip：点击填入输入框（不发送），光标在末尾 */
    private void renderTemplates() {
        ViewGroup group = binding.askTemplateGroup;
        group.removeAllViews();
        LayoutInflater inflater = getLayoutInflater();
        for (String template : viewModel.getTemplates()) {
            Chip chip = (Chip) inflater.inflate(R.layout.item_ai_template_chip, group, false);
            chip.setText(template);
            chip.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    lastTemplateTouch = SystemClock.uptimeMillis();
                }
                return false;
            });
            chip.setOnClickListener(v -> {
                lastTemplateTouch = SystemClock.uptimeMillis();
                viewModel.onTemplateClick(template);
            });
            group.addView(chip);
        }
    }

    /**
     * 由 MainActivity 询问：点提问模板、待发送图片、输入栏时不按「点击输入框外收起键盘」处理，
     * 避免键盘先收起再弹出造成闪动、底部导航短暂出现。
     */
    @Override
    public boolean shouldHideKeyboardOnTouch(@NonNull MotionEvent ev) {
        if (binding == null) {
            return true;
        }
        return !(isTouchInside(binding.askTemplates, ev)
                || isTouchInside(binding.askPending, ev)
                || isTouchInside(binding.askInputBar, ev));
    }

    private static boolean isTouchInside(@NonNull View view, @NonNull MotionEvent ev) {
        if (view.getVisibility() != View.VISIBLE) {
            return false;
        }
        Rect rect = new Rect();
        return view.getGlobalVisibleRect(rect) && rect.contains((int) ev.getRawX(), (int) ev.getRawY());
    }

    /**
     * 监听软键盘显示 / 收起：收起后隐藏模板行。
     * 点模板 Chip 时 BaseActivity 会在按下时先收起键盘（点击输入框以外收起键盘），随后点击回调重新弹出，
     * 因此延迟确认键盘仍是收起状态、且不是刚点过模板时才隐藏。
     */
    private void watchKeyboard(@NonNull View root) {
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            boolean visible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeVisible && !visible) {
                v.removeCallbacks(keyboardHiddenCheck);
                v.postDelayed(keyboardHiddenCheck, KEYBOARD_HIDE_DELAY_MS);
            }
            imeVisible = visible;
            return insets;
        });
    }

    private void checkKeyboardHidden() {
        if (binding == null) {
            return;
        }
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(binding.getRoot());
        boolean visible = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
        boolean recentTemplate = SystemClock.uptimeMillis() - lastTemplateTouch < TEMPLATE_TOUCH_GRACE_MS;
        if (!visible && !recentTemplate) {
            viewModel.onKeyboardHidden();
        }
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
            case AskAiViewModel.Event.PICK_IMAGE:
                pickImage.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                        .build());
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
                focusInput();
                break;
            case AskAiViewModel.Event.SHOW_IMAGE_SOURCE:
                hideKeyboard();
                showSheet(new ImageSourceSheet(), TAG_IMAGE_SOURCE);
                break;
            case AskAiViewModel.Event.TAKE_PHOTO:
                takePhoto();
                break;
            case AskAiViewModel.Event.SHOW_RECORD_PICKER:
                hideKeyboard();
                showSheet(new RecordPickerSheet(), TAG_RECORD_PICKER);
                break;
            case AskAiViewModel.Event.OPEN_SCAN:
                startActivity(RouterPath.intent(requireContext(), RouterPath.SCAN)
                        .putExtra(RouterPath.EXTRA_MODE, RouterPath.MODE_PEST));
                break;
            default:
                break;
        }
    }

    private void showSheet(@NonNull DialogFragment sheet, @NonNull String tag) {
        FragmentManager fm = getChildFragmentManager();
        if (fm.findFragmentByTag(tag) == null && !fm.isStateSaved()) {
            sheet.show(fm, tag);
        }
    }

    private void openChat(@NonNull ChatArgs args) {
        hideKeyboard();
        startActivity(ChatActivity.intent(requireContext(), args));
    }

    private void hideKeyboard() {
        binding.askInput.clearFocus();
        KeyboardUtils.hide(binding.askInput);
    }

    /** 输入框获得焦点、光标移到末尾并弹出键盘 */
    private void focusInput() {
        EditText input = binding.askInput;
        if (!input.isFocusable()) {
            return;
        }
        input.requestFocus();
        Editable text = input.getText();
        input.setSelection(text == null ? 0 : text.length());
        // 从相机 / 照片选择器返回时窗口可能还没获得焦点：等获得焦点后再弹出键盘
        removeFocusWaiter();
        if (input.hasWindowFocus()) {
            input.post(this::showKeyboard);
            return;
        }
        focusWaiter = hasFocus -> {
            if (hasFocus) {
                removeFocusWaiter();
                if (binding != null) {
                    binding.askInput.post(this::showKeyboard);
                }
            }
        };
        input.getViewTreeObserver().addOnWindowFocusChangeListener(focusWaiter);
    }

    private void showKeyboard() {
        if (binding != null && getActivity() != null && binding.askInput.hasFocus()) {
            WindowCompat.getInsetsController(requireActivity().getWindow(), binding.askInput)
                    .show(WindowInsetsCompat.Type.ime());
        }
    }

    private void removeFocusWaiter() {
        if (focusWaiter != null && binding != null) {
            ViewTreeObserver observer = binding.askInput.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnWindowFocusChangeListener(focusWaiter);
            }
        }
        focusWaiter = null;
    }

    // ==================== 拍照 ====================

    private void takePhoto() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCamera();
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCamera() {
        Uri uri = null;
        try {
            uri = AiFileProvider.createCameraUri(requireContext());
            pendingCameraUri = uri;
            takePicture.launch(uri);
        } catch (IOException | ActivityNotFoundException | IllegalArgumentException e) {
            pendingCameraUri = null;
            if (uri != null) {
                AiFileProvider.deleteQuietly(requireContext(), uri);
            }
            showSnackbar(getString(R.string.ai_camera_unavailable));
        }
    }
}
