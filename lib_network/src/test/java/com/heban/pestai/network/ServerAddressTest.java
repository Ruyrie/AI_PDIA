package com.heban.pestai.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.Nullable;

import org.junit.Test;

public class ServerAddressTest {

    private static final String DEFAULT = "http://10.0.2.2:8080";

    /** 不依赖 Context：保存的地址放在内存里 */
    static class InMemoryServerAddress extends ServerAddress {
        @Nullable
        String saved;
        int writes;

        InMemoryServerAddress(String defaultServer) {
            super(null, defaultServer);
        }

        @Nullable
        @Override
        protected String readSaved() {
            return saved;
        }

        @Override
        protected void writeSaved(@Nullable String value) {
            writes++;
            saved = value;
        }
    }

    @Test
    public void normalizeAcceptsCommonForms() {
        assertEquals("http://47.100.1.2", ServerAddress.normalize("47.100.1.2"));
        assertEquals("http://47.100.1.2:8080", ServerAddress.normalize("47.100.1.2:8080"));
        assertEquals("http://47.100.1.2", ServerAddress.normalize("http://47.100.1.2/"));
        assertEquals("http://47.100.1.2:8080", ServerAddress.normalize("47.100.1.2:8080/"));
        assertEquals("http://example.com", ServerAddress.normalize("example.com"));
        assertEquals("http://10.0.2.2:8080", ServerAddress.normalize("10.0.2.2:8080"));
    }

    @Test
    public void normalizeTrimsAndDropsDefaultPort() {
        assertEquals("http://47.100.1.2", ServerAddress.normalize("  http://47.100.1.2:80  "));
        assertEquals("https://example.com", ServerAddress.normalize("HTTPS://Example.COM:443"));
        assertEquals("https://example.com:8443", ServerAddress.normalize("https://example.com:8443/"));
    }

    @Test
    public void normalizeAcceptsChinesePunctuation() {
        // 中文输入法下的冒号、句号
        assertEquals("http://47.100.1.2:8080", ServerAddress.normalize("47.100.1.2：8080"));
        assertEquals("http://47.100.1.2", ServerAddress.normalize("47。100。1。2"));
    }

    @Test
    public void normalizeRejectsInvalidInput() {
        assertNull(ServerAddress.normalize(null));
        assertNull(ServerAddress.normalize(""));
        assertNull(ServerAddress.normalize("   "));
        assertNull(ServerAddress.normalize("http://"));
        assertNull(ServerAddress.normalize("ftp://47.100.1.2"));
        assertNull(ServerAddress.normalize("47.100.1.2/pestai/v1/"));
        assertNull(ServerAddress.normalize("http://47.100.1.2/api"));
        assertNull(ServerAddress.normalize("47.100.1.2?a=1"));
        assertNull(ServerAddress.normalize("47.100.1.2#top"));
        assertNull(ServerAddress.normalize("user:pw@47.100.1.2"));
        assertNull(ServerAddress.normalize("47.100.1.2:99999"));
        assertNull(ServerAddress.normalize("47.100.1.2:abc"));
        assertNull(ServerAddress.normalize("http://a b.com"));
    }

    @Test
    public void defaultWhenNothingSaved() {
        InMemoryServerAddress address = new InMemoryServerAddress(DEFAULT + "/");
        assertEquals(DEFAULT, address.getDefault());
        assertEquals(DEFAULT, address.current());
        assertFalse(address.isCustom());
    }

    @Test
    public void setNormalizesAndSaves() {
        InMemoryServerAddress address = new InMemoryServerAddress(DEFAULT);
        assertTrue(address.set(" 47.100.1.2 "));
        assertEquals("http://47.100.1.2", address.current());
        assertEquals("http://47.100.1.2", address.saved);
        assertTrue(address.isCustom());
    }

    @Test
    public void setInvalidChangesNothing() {
        InMemoryServerAddress address = new InMemoryServerAddress(DEFAULT);
        assertFalse(address.set("http://47.100.1.2/pestai"));
        assertEquals(DEFAULT, address.current());
        assertEquals(0, address.writes);
    }

    @Test
    public void setDefaultOrResetRemovesSavedAddress() {
        InMemoryServerAddress address = new InMemoryServerAddress(DEFAULT);
        address.set("47.100.1.2");
        assertTrue(address.set("10.0.2.2:8080"));
        assertFalse(address.isCustom());
        assertNull(address.saved);

        address.set("47.100.1.2");
        address.reset();
        assertFalse(address.isCustom());
        assertEquals(DEFAULT, address.current());
        assertNull(address.saved);
    }

    @Test
    public void loadsSavedAddressOnce() {
        InMemoryServerAddress address = new InMemoryServerAddress(DEFAULT);
        address.saved = "http://47.100.1.2:8080";
        assertEquals("http://47.100.1.2:8080", address.current());
        // 已加载到内存，之后不再读取
        address.saved = "http://1.1.1.1";
        assertEquals("http://47.100.1.2:8080", address.current());
    }

    @Test
    public void ignoresInvalidOrDefaultSavedValue() {
        InMemoryServerAddress invalid = new InMemoryServerAddress(DEFAULT);
        invalid.saved = "not a url/";
        assertEquals(DEFAULT, invalid.current());
        assertFalse(invalid.isCustom());

        InMemoryServerAddress same = new InMemoryServerAddress(DEFAULT);
        same.saved = DEFAULT + "/";
        assertFalse(same.isCustom());
    }
}
