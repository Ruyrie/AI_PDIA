package com.heban.pestai.ai;

import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 单元测试用的内存 SharedPreferences（apply / commit 立即生效，记录写入次数） */
public class FakeSharedPreferences implements SharedPreferences {

    private final Map<String, Object> values = new HashMap<>();
    /** apply / commit 的次数 */
    public int writes;

    @Override
    public Map<String, ?> getAll() {
        return new HashMap<>(values);
    }

    @Nullable
    @Override
    public String getString(String key, @Nullable String defValue) {
        Object v = values.get(key);
        return v instanceof String ? (String) v : defValue;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public Set<String> getStringSet(String key, @Nullable Set<String> defValues) {
        Object v = values.get(key);
        return v instanceof Set ? (Set<String>) v : defValues;
    }

    @Override
    public int getInt(String key, int defValue) {
        Object v = values.get(key);
        return v instanceof Integer ? (Integer) v : defValue;
    }

    @Override
    public long getLong(String key, long defValue) {
        Object v = values.get(key);
        return v instanceof Long ? (Long) v : defValue;
    }

    @Override
    public float getFloat(String key, float defValue) {
        Object v = values.get(key);
        return v instanceof Float ? (Float) v : defValue;
    }

    @Override
    public boolean getBoolean(String key, boolean defValue) {
        Object v = values.get(key);
        return v instanceof Boolean ? (Boolean) v : defValue;
    }

    @Override
    public boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    @Override
    public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
    }

    private final class FakeEditor implements Editor {

        private final Map<String, Object> pending = new HashMap<>();
        private final Set<String> removed = new HashSet<>();
        private boolean clear;

        @Override
        public Editor putString(String key, @Nullable String value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, @Nullable Set<String> values) {
            pending.put(key, values == null ? null : new HashSet<>(values));
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            pending.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            removed.add(key);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            apply();
            return true;
        }

        @Override
        public void apply() {
            writes++;
            if (clear) {
                values.clear();
            }
            for (String key : removed) {
                values.remove(key);
            }
            for (Map.Entry<String, Object> e : pending.entrySet()) {
                if (e.getValue() == null) {
                    values.remove(e.getKey());
                } else {
                    values.put(e.getKey(), e.getValue());
                }
            }
        }
    }
}
