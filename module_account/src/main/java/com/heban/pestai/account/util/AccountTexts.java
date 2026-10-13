package com.heban.pestai.account.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * ViewModel 中生成的提示文案（取自模块02、03 UI 设计文档「页面状态与提示文案」与《找回账号与恢复码》）。
 * 页面上固定显示的文字放在 res/values/strings.xml；这里只放 ViewModel 运行时拼接或提示的文案，便于单元测试断言。
 */
public final class AccountTexts {

    public static final String PHONE_INVALID = "请输入正确的手机号";
    public static final String PASSWORD_EMPTY = "请输入密码";
    public static final String CODE_INVALID = "请输入 6 位验证码";

    public static final String NOT_REGISTERED_LOGIN = "该手机号未注册，可以使用验证码登录自动注册";
    public static final String NOT_REGISTERED = "该手机号未注册";
    public static final String WRONG_PASSWORD = "账号或密码错误";
    public static final String CODE_EXPIRED = "验证码错误或已过期";
    public static final String PHONE_REGISTERED_REGISTER = "该手机号已注册，请直接登录";
    /** A09：40901 新手机号已注册 */
    public static final String PHONE_REGISTERED_REBIND = "该手机号已注册，无法用于换绑";
    public static final String ACCOUNT_DISABLED = "该账号已停用";

    public static final String PASSWORD_RULE = "密码需为 8–20 位，并同时包含字母和数字";
    public static final String CONFIRM_MISMATCH = "两次输入的密码不一致";
    public static final String CONFIRM_MATCHED = "两次输入一致";
    /** auth/password/reset 返回 40001 时的两种固定文案（前后端约定） */
    public static final String SAME_PASSWORD = "新密码不能与最近使用过的密码相同";
    public static final String TICKET_EXPIRED = "验证已过期，请重新获取验证码";

    public static final String NICKNAME_EMPTY = "请填写昵称";
    public static final String NICKNAME_TOO_LONG = "昵称最多 16 个字";
    public static final String AVATAR_UPLOAD_FAILED = "头像上传失败，可稍后重试";

    public static final String SAME_PHONE = "新手机号不能与原手机号相同";

    // ---------- A09 找回账号 ----------
    /** 第 2 步验证码下方的默认说明 */
    public static final String REBIND_CODE_HINT = "用于确认新手机号是你本人在用";
    /** 恢复码输入框下方的默认说明 */
    public static final String RECOVERY_CODE_HINT = "在『设置 → 账号恢复码』生成的 12 位恢复码";
    /** 恢复码含字符集之外的字符（本地校验） */
    public static final String RECOVERY_CODE_CHARS = "恢复码不含数字 0、1 和字母 O、I，请核对";
    /** 40105（未生成也返回它） */
    public static final String RECOVERY_CODE_INVALID = "恢复码错误或已失效";
    /** auth/recover/rebind 凭证无效 / 过期 / 已用时 40001 的固定文案（前后端约定），回到第 1 步时也提示这句 */
    public static final String REBIND_EXPIRED = "验证已过期，请重新验证身份";
    /** 判断 40001 是否为凭证过期：以此开头 */
    public static final String REBIND_EXPIRED_PREFIX = "验证已过期";

    // ---------- A12 账号恢复码 ----------
    public static final String RECOVERY_STATUS_NONE = "当前状态：未生成";
    public static final String RECOVERY_STATUS_LOADING = "当前状态：查询中…";
    public static final String RECOVERY_STATUS_UNKNOWN = "当前状态：查询失败";
    public static final String RECOVERY_COPIED = "已复制，请妥善保存";
    /** 免验证直接生成时服务器返回 40001「请先验证当前手机号」（10 分钟窗口刚好过期），切换为验证码界面后提示 */
    public static final String RECOVERY_VERIFY_EXPIRED = "距离上次短信验证已超过 10 分钟，请先验证当前手机号";

    /** A10：新号与当前号相同（本地校验；user/phone/sms 返回 40001 时 message 也是这句） */
    public static final String SAME_CURRENT_PHONE = "新手机号不能与当前手机号相同";
    /** A10：40901 新手机号已被其他账号使用 */
    public static final String PHONE_REGISTERED_CHANGE = "该手机号已注册，请先注销该账号或更换其他号码";
    /** A11：当前手机号未知时的已发送提示 */
    public static final String SENT_TO_CURRENT = "验证码已发送至当前手机号，5 分钟内有效";

    public static final String VERSION_PREFIX = "AI 病虫害识别 · v";

    private AccountTexts() {
    }

    /** A04 / A05 / A09：验证码已发送至 138****5678，5 分钟内有效 */
    @NonNull
    public static String sentHintLong(@Nullable String phone) {
        return "验证码已发送至 " + PhoneRules.mask(phone) + "，5 分钟内有效";
    }

    /** A10 成功态：今后请使用 139****1111 登录 */
    @NonNull
    public static String changePhoneSuccess(@Nullable String phone) {
        return "今后请使用 " + PhoneRules.mask(phone) + " 登录";
    }

    /** A09 成功态：今后请使用 139****1111 登录，已为你自动登录 */
    @NonNull
    public static String rebindSuccess(@Nullable String phone) {
        return "今后请使用 " + PhoneRules.mask(phone) + " 登录，已为你自动登录";
    }

    /** A12：当前状态：已生成（2026年10月13日）；日期未知时只显示「已生成」 */
    @NonNull
    public static String recoveryStatusGenerated(@Nullable LocalDate createdAt) {
        if (createdAt == null) {
            return "当前状态：已生成";
        }
        return "当前状态：已生成（" + DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA).format(createdAt) + "）";
    }

    /** A12：验证当前手机号 138****5678；号码未知时只显示「验证当前手机号」 */
    @NonNull
    public static String verifyCurrentPhone(@Nullable String phone) {
        String masked = PhoneRules.mask(phone);
        return masked.isEmpty() ? "验证当前手机号" : "验证当前手机号 " + masked;
    }

    /**
     * A08 / A09（恢复码方式）新密码输入框下方的错误提示：输入满 8 位后仍不符合规则才提示，避免刚开始输入就报错；否则为 null。
     */
    @Nullable
    public static String passwordError(@Nullable String password) {
        if (password == null || password.length() < PasswordRules.MIN_LENGTH) {
            return null;
        }
        return PasswordRules.isValid(password) ? null : PASSWORD_RULE;
    }

    /** A07：已发送至 138****5678 */
    @NonNull
    public static String sentHintShort(@Nullable String phone) {
        return "已发送至 " + PhoneRules.mask(phone);
    }

    /** A06：你主要种什么？（已选 3 项，可多选） */
    @NonNull
    public static String cropTitle(int count) {
        return "你主要种什么？（已选 " + count + " 项，可多选）";
    }

    /** 闪屏：AI 病虫害识别 · v1.0.0 */
    @NonNull
    public static String version(@Nullable String versionName) {
        return VERSION_PREFIX + (versionName == null || versionName.isEmpty() ? "1.0.0" : versionName);
    }
}
