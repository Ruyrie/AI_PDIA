package com.heban.pestai.ai.voice;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** WAV 文件头与写入（PCM 16 位 / 16kHz / 单声道，后端 POST ai/asr 的格式） */
public class WavWriterTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private static String ascii(byte[] b, int offset) {
        return new String(b, offset, 4, StandardCharsets.US_ASCII);
    }

    private static int intLE(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static int shortLE(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
    }

    @Test
    public void header_pcm16_16k_mono() {
        byte[] h = WavWriter.header(32_000);
        assertEquals(44, h.length);
        assertEquals("RIFF", ascii(h, 0));
        assertEquals("RIFF 块长度 = 36 + 数据长度", 36 + 32_000, intLE(h, 4));
        assertEquals("WAVE", ascii(h, 8));
        assertEquals("fmt ", ascii(h, 12));
        assertEquals(16, intLE(h, 16));
        assertEquals("PCM", 1, shortLE(h, 20));
        assertEquals("单声道", 1, shortLE(h, 22));
        assertEquals(16_000, intLE(h, 24));
        assertEquals("ByteRate", 32_000, intLE(h, 28));
        assertEquals("BlockAlign", 2, shortLE(h, 32));
        assertEquals("BitsPerSample", 16, shortLE(h, 34));
        assertEquals("data", ascii(h, 36));
        assertEquals(32_000, intLE(h, 40));
    }

    @Test
    public void header_exactBytes() {
        byte[] expected = {
                'R', 'I', 'F', 'F', 0x2C, 0, 0, 0, 'W', 'A', 'V', 'E',
                'f', 'm', 't', ' ', 0x10, 0, 0, 0, 0x01, 0, 0x01, 0,
                (byte) 0x80, 0x3E, 0, 0, 0x00, 0x7D, 0, 0, 0x02, 0, 0x10, 0,
                'd', 'a', 't', 'a', 0x08, 0, 0, 0};
        assertArrayEquals(expected, WavWriter.header(8));
    }

    @Test
    public void header_emptyData() {
        byte[] h = WavWriter.header(0);
        assertEquals(36, intLE(h, 4));
        assertEquals(0, intLE(h, 40));
    }

    @Test
    public void writeAndFinish_patchesSizes_littleEndianSamples() throws IOException {
        File file = new File(temp.getRoot(), "voice.wav");
        WavWriter writer = WavWriter.open(file, WavWriter.bytesFor(VoiceGesture.MAX_DURATION_MS));
        assertEquals("打开后先写入 44 字节文件头", 44, file.length());
        short[] samples = {0, 1, -1, 0x1234, Short.MIN_VALUE, Short.MAX_VALUE};
        assertTrue(writer.write(samples, 4));
        assertTrue(writer.write(new short[]{Short.MIN_VALUE, Short.MAX_VALUE}, 2));
        assertEquals(12, writer.getDataBytes());
        assertEquals(12, writer.finish());

        byte[] b = Files.readAllBytes(file.toPath());
        assertEquals(44 + 12, b.length);
        assertEquals(36 + 12, intLE(b, 4));
        assertEquals(12, intLE(b, 40));
        byte[] data = Arrays.copyOfRange(b, 44, b.length);
        assertArrayEquals(new byte[]{0, 0, 1, 0, (byte) 0xFF, (byte) 0xFF, 0x34, 0x12, 0x00, (byte) 0x80,
                (byte) 0xFF, 0x7F}, data);
        // 其余文件头字段与纯计算一致
        assertArrayEquals(Arrays.copyOfRange(WavWriter.header(12), 0, 44), Arrays.copyOfRange(b, 0, 44));
    }

    @Test
    public void write_countLimitsSamples() throws IOException {
        File file = new File(temp.getRoot(), "count.wav");
        WavWriter writer = WavWriter.open(file, 1_000);
        assertTrue("只写前 count 个采样", writer.write(new short[]{1, 2, 3, 4, 5}, 2));
        assertTrue("count 超出数组长度按数组长度", writer.write(new short[]{6}, 10));
        assertEquals(6, writer.getDataBytes());
        writer.finish();
        assertEquals(50, file.length());
    }

    @Test
    public void write_dropsSamplesBeyondMax() throws IOException {
        File file = new File(temp.getRoot(), "max.wav");
        // 7 字节按采样帧对齐为 6 字节（3 个采样）
        WavWriter writer = WavWriter.open(file, 7);
        assertFalse("超出上限的采样丢弃", writer.write(new short[]{1, 2, 3, 4, 5}, 5));
        assertEquals(6, writer.getDataBytes());
        assertFalse(writer.write(new short[]{6}, 1));
        assertTrue("0 个采样不算丢弃", writer.write(new short[0], 0));
        assertEquals(6, writer.finish());
        byte[] b = Files.readAllBytes(file.toPath());
        assertEquals(50, b.length);
        assertEquals(6, intLE(b, 40));
        assertEquals(42, intLE(b, 4));
    }

    @Test
    public void open_createsParentDirectory_andOverwrites() throws IOException {
        File file = new File(new File(temp.getRoot(), "ai_voice"), "a.wav");
        WavWriter first = WavWriter.open(file, 100);
        first.write(new short[]{1, 2, 3}, 3);
        first.finish();
        assertEquals(50, file.length());

        WavWriter second = WavWriter.open(file, 100);
        second.write(new short[]{9}, 1);
        second.finish();
        assertEquals("重新打开时覆盖旧内容", 46, file.length());
    }

    @Test
    public void abort_deletesFile() throws IOException {
        File file = new File(temp.getRoot(), "abort.wav");
        WavWriter writer = WavWriter.open(file, 100);
        writer.write(new short[]{1, 2}, 2);
        writer.abort();
        assertFalse(file.exists());
        writer.abort();
        assertFalse("可重复调用", file.exists());
    }

    @Test
    public void finish_twiceOrWriteAfterFinish_throws() throws IOException {
        File file = new File(temp.getRoot(), "closed.wav");
        WavWriter writer = WavWriter.open(file, 100);
        writer.finish();
        try {
            writer.write(new short[]{1}, 1);
            fail("关闭后不能再写");
        } catch (IOException expected) {
            // ok
        }
        try {
            writer.finish();
            fail("不能重复 finish");
        } catch (IOException expected) {
            // ok
        }
        assertEquals("文件保留", 44, file.length());
    }

    @Test
    public void sixtySeconds_fitsUnderTwoMegabytes() {
        assertEquals(32_000, WavWriter.BYTE_RATE);
        long max = WavWriter.bytesFor(VoiceGesture.MAX_DURATION_MS);
        assertEquals(1_920_000, max);
        assertTrue("60 秒加文件头不超过 2MB（按 2,000,000 字节算也不超）", max + WavWriter.HEADER_SIZE <= 2_000_000);
        assertEquals(32_000, WavWriter.bytesFor(1_000));
        assertEquals("按采样帧对齐", 0, WavWriter.bytesFor(1) % 2);
        assertEquals(0, WavWriter.bytesFor(-5));
        assertEquals(1_000, WavWriter.durationMs(32_000));
        assertEquals(60_000, WavWriter.durationMs(max));
    }
}
