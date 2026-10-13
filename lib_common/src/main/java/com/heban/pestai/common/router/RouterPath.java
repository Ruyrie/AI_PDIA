package com.heban.pestai.common.router;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/**
 * 页面路由常量（公共数据 05「五、页面路由常量」）。
 * 组件间跳转使用隐式 Intent：每个页面在所属组件的 AndroidManifest 中声明 action
 * （intent-filter 中同时声明 category DEFAULT，activity 设 exported="false"），
 * 调用方只引用这里的常量，不直接引用其他组件的类。
 *
 * <pre>
 * Intent intent = RouterPath.intent(context, RouterPath.SCAN)
 *         .putExtra(RouterPath.EXTRA_MODE, RouterPath.MODE_PEST);
 * context.startActivity(intent);
 * </pre>
 */
public final class RouterPath {

    private static final String PREFIX = "com.heban.pestai.action.";

    // ---------- action ----------
    /** A01 闪屏（module_account） */
    public static final String SPLASH = PREFIX + "SPLASH";
    /** A02 引导页（module_account） */
    public static final String ONBOARDING = PREFIX + "ONBOARDING";
    /** A03 / A04 登录（module_account） */
    public static final String LOGIN = PREFIX + "LOGIN";
    /** A05 注册（module_account） */
    public static final String REGISTER = PREFIX + "REGISTER";
    /** A06 完善资料（module_account） */
    public static final String PROFILE_SETUP = PREFIX + "PROFILE_SETUP";
    /** A07 找回密码（module_account） */
    public static final String FORGOT_PWD = PREFIX + "FORGOT_PWD";
    /** A08 设置新密码（module_account） */
    public static final String RESET_PWD = PREFIX + "RESET_PWD";
    /** A09 找回账号：原手机号不可用时，用登录密码或账号恢复码验证身份后换绑新手机号（module_account） */
    public static final String RECOVER_ACCOUNT = PREFIX + "RECOVER_ACCOUNT";
    /** A10 更换手机号（登录状态下验证新手机号即可换绑，module_account） */
    public static final String CHANGE_PHONE = PREFIX + "CHANGE_PHONE";
    /** A11 注销账号（验证当前手机号后立即删除账号与全部数据，module_account） */
    public static final String DEACTIVATE = PREFIX + "DEACTIVATE";
    /** A12 账号恢复码：生成 / 重新生成恢复码（module_account） */
    public static final String RECOVERY_CODE = PREFIX + "RECOVERY_CODE";
    /** 主页面：H01 / Q01 / M01 三个 Tab（app） */
    public static final String MAIN = PREFIX + "MAIN";
    /** S01 拍照识别（module_recognize） */
    public static final String SCAN = PREFIX + "SCAN";
    /** S02 识别中（module_recognize） */
    public static final String ANALYZING = PREFIX + "ANALYZING";
    /** R01 识别结果（module_recognize） */
    public static final String SCAN_RESULT = PREFIX + "SCAN_RESULT";
    /** R02 识别异常（module_recognize） */
    public static final String SCAN_ERROR = PREFIX + "SCAN_ERROR";
    /** R03 / R04 识别记录（module_record） */
    public static final String HISTORY = PREFIX + "HISTORY";
    /** Q02 AI 对话（module_ai） */
    public static final String AI_CHAT = PREFIX + "AI_CHAT";
    /** M02 设置（module_mine） */
    public static final String SETTINGS = PREFIX + "SETTINGS";
    /** M03 关于（module_mine） */
    public static final String ABOUT = PREFIX + "ABOUT";
    /** 用户协议 / 隐私政策本地文本页（module_mine） */
    public static final String AGREEMENT = PREFIX + "AGREEMENT";

    // ---------- Intent 参数键 ----------
    /** String：识别模式，取值 MODE_PEST / MODE_CROP */
    public static final String EXTRA_MODE = "mode";
    /** String：待识别照片的本机 Uri */
    public static final String EXTRA_IMAGE_URI = "image_uri";
    /** long：识别记录 ID，无记录时为 -1 */
    public static final String EXTRA_RECORD_ID = "record_id";
    /** String：服务器上的照片地址（未成功记录进入 R02 时显示原照片） */
    public static final String EXTRA_IMAGE_URL = "image_url";
    /** String：Gson 序列化的 RecognitionVO，S02 / R02 进入 R01 时携带 */
    public static final String EXTRA_RECORD_JSON = "record_json";
    /** String：IMAGE_BAD / FAILED（见 ErrorType） */
    public static final String EXTRA_ERROR_TYPE = "error_type";
    /** String：失败原因码 TIMEOUT / UNAVAILABLE / IMAGE_BAD / NETWORK（见 FailReason） */
    public static final String EXTRA_FAIL_REASON = "fail_reason";
    /** boolean：从 Q02 引用卡进入 R01，点「追问 AI」直接返回原对话 */
    public static final String EXTRA_FROM_CHAT = "from_chat";
    /** String：预填当前账号手机号（M02 → A07） */
    public static final String EXTRA_PHONE = "phone";
    /** boolean：从设置页进入修改密码（M02 → A07） */
    public static final String EXTRA_FROM_SETTINGS = "from_settings";
    /** int：MainActivity 默认选中的 Tab，取值见 MainViewModel.TAB_HOME / TAB_AI / TAB_MINE */
    public static final String EXTRA_TAB = "tab";
    /** String：用户协议页显示的文本类型，取值 AGREEMENT_USER / AGREEMENT_PRIVACY */
    public static final String EXTRA_AGREEMENT_TYPE = "agreement_type";

    // ---------- 参数取值 ----------
    public static final String MODE_PEST = "PEST";
    public static final String MODE_CROP = "CROP";
    public static final long NO_RECORD = -1L;
    public static final String AGREEMENT_USER = "USER";
    public static final String AGREEMENT_PRIVACY = "PRIVACY";

    private RouterPath() {
    }

    /** 创建限定为本应用包名的隐式 Intent（只在本 App 内匹配） */
    @NonNull
    public static Intent intent(@NonNull Context context, @NonNull String action) {
        return new Intent(action).setPackage(context.getPackageName());
    }

    /** 打开页面（无参数） */
    public static void open(@NonNull Context context, @NonNull String action) {
        context.startActivity(intent(context, action));
    }

    /** 清空任务栈后打开页面（登录成功进入主页、退出登录回到登录页等） */
    public static void openClearTask(@NonNull Context context, @NonNull String action) {
        context.startActivity(intent(context, action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
