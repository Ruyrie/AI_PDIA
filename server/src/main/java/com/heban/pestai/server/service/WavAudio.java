package com.heban.pestai.server.service;

import java.nio.charset.StandardCharsets;

import com.heban.pestai.server.common.BizException;

/**
 * WAV 文件头解析（语音识别上传前的校验）：RIFF/WAVE 容器，逐个 chunk 查找「fmt 」与「data」，
 * 编码必须是 PCM（audio_format = 1，或 WAVE_FORMAT_EXTENSIBLE 且子格式为 PCM）。
 * 时长 = data 字节数 / 每秒字节数（byte_rate）；data 长度写得比实际文件大（边录边写未回填）时按实际剩余字节计算。
 */
public final class WavAudio {

    private static final int FORMAT_PCM = 1;
    private static final int FORMAT_EXTENSIBLE = 0xFFFE;

    public static final String NOT_WAV = "只支持 WAV 格式的录音";
    public static final String NOT_PCM = "只支持 PCM 编码的 WAV 录音";
    public static final String BROKEN = "录音文件不完整，请重新录音";

    /** 解析结果 */
    public record Info(int channels, int sampleRate, int bitsPerSample, long byteRate, long dataBytes) {

        /** 时长（毫秒） */
        public long durationMillis() {
            return byteRate <= 0 ? 0 : dataBytes * 1000 / byteRate;
        }
    }

    private WavAudio() {
    }

    /** 解析并校验；不是 RIFF/WAVE、不是 PCM、缺少 fmt / data 时抛出 40001 */
    public static Info parse(byte[] bytes) {
        if (bytes == null || bytes.length < 12 || !"RIFF".equals(ascii(bytes, 0)) || !"WAVE".equals(ascii(bytes, 8))) {
            throw BizException.param(NOT_WAV);
        }
        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        long byteRate = 0;
        Long dataBytes = null;
        int pos = 12;
        while (pos + 8 <= bytes.length) {
            String id = ascii(bytes, pos);
            long size = u32(bytes, pos + 4);
            int body = pos + 8;
            if ("fmt ".equals(id)) {
                if (size < 16 || body + 16 > bytes.length) {
                    throw BizException.param(BROKEN);
                }
                format = u16(bytes, body);
                channels = u16(bytes, body + 2);
                sampleRate = (int) u32(bytes, body + 4);
                byteRate = u32(bytes, body + 8);
                bits = u16(bytes, body + 14);
                // WAVE_FORMAT_EXTENSIBLE：cbSize(2) + validBits(2) + channelMask(4) 之后是子格式 GUID，前 2 字节为格式码
                if (format == FORMAT_EXTENSIBLE && size >= 40 && body + 26 <= bytes.length) {
                    format = u16(bytes, body + 24);
                }
            } else if ("data".equals(id)) {
                dataBytes = Math.min(size, (long) bytes.length - body);
                break;
            }
            long next = body + size + (size & 1);
            if (next > bytes.length) {
                break;
            }
            pos = (int) next;
        }
        if (format < 0 || dataBytes == null) {
            throw BizException.param(BROKEN);
        }
        if (format != FORMAT_PCM) {
            throw BizException.param(NOT_PCM);
        }
        if (channels <= 0 || bits <= 0 || byteRate <= 0) {
            throw BizException.param(BROKEN);
        }
        return new Info(channels, sampleRate, bits, byteRate, dataBytes);
    }

    private static String ascii(byte[] bytes, int offset) {
        return new String(bytes, offset, 4, StandardCharsets.US_ASCII);
    }

    private static int u16(byte[] b, int offset) {
        return (b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8);
    }

    private static long u32(byte[] b, int offset) {
        return (b[offset] & 0xFFL) | ((b[offset + 1] & 0xFFL) << 8) | ((b[offset + 2] & 0xFFL) << 16)
                | ((b[offset + 3] & 0xFFL) << 24);
    }

    /** 生成一段 16 位、单声道 PCM 的 WAV（全静音），用于配置测试与单元测试 */
    public static byte[] silence(int sampleRate, int millis) {
        int dataBytes = sampleRate * 2 * millis / 1000;
        byte[] wav = new byte[44 + dataBytes];
        putAscii(wav, 0, "RIFF");
        putU32(wav, 4, 36L + dataBytes);
        putAscii(wav, 8, "WAVE");
        putAscii(wav, 12, "fmt ");
        putU32(wav, 16, 16);
        putU16(wav, 20, FORMAT_PCM);
        putU16(wav, 22, 1);
        putU32(wav, 24, sampleRate);
        putU32(wav, 28, sampleRate * 2L);
        putU16(wav, 32, 2);
        putU16(wav, 34, 16);
        putAscii(wav, 36, "data");
        putU32(wav, 40, dataBytes);
        return wav;
    }

    private static void putAscii(byte[] b, int offset, String s) {
        byte[] ascii = s.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(ascii, 0, b, offset, ascii.length);
    }

    private static void putU16(byte[] b, int offset, int v) {
        b[offset] = (byte) v;
        b[offset + 1] = (byte) (v >>> 8);
    }

    private static void putU32(byte[] b, int offset, long v) {
        b[offset] = (byte) v;
        b[offset + 1] = (byte) (v >>> 8);
        b[offset + 2] = (byte) (v >>> 16);
        b[offset + 3] = (byte) (v >>> 24);
    }
}
