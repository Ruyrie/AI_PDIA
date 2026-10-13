package com.heban.pestai.recognize.model;

/**
 * R02 一次性事件。跳转所需参数由 Activity 从 ScanErrorViewModel 读取。
 */
public enum ErrorEvent {
    /** 重拍：回到相机 S01（FLAG_ACTIVITY_CLEAR_TOP），保持原识别模式 */
    RETAKE,
    /** 没有识别记录：带本机照片进入 S02 重新上传 */
    REUPLOAD,
    /** 重新识别成功：进入 R01 显示新结果 */
    GO_RESULT,
    /** 识别记录 R03 */
    OPEN_HISTORY
}
