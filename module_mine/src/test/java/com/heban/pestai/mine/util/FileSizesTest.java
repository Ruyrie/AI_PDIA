package com.heban.pestai.mine.util;

import static org.junit.Assert.assertEquals;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class FileSizesTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void nullOrMissingIsZero() {
        assertEquals(0, FileSizes.sizeOf(null));
        assertEquals(0, FileSizes.sizeOf(new File(tmp.getRoot(), "missing")));
    }

    @Test
    public void emptyDirectoryIsZero() throws IOException {
        assertEquals(0, FileSizes.sizeOf(tmp.newFolder("image_manager_disk_cache")));
    }

    @Test
    public void singleFileLength() throws IOException {
        File file = write(tmp.newFile("a.0"), 300);
        assertEquals(300, FileSizes.sizeOf(file));
    }

    @Test
    public void sumsNestedFilesRecursively() throws IOException {
        File root = tmp.newFolder("image_manager_disk_cache");
        write(new File(root, "journal"), 120);
        write(new File(root, "a.0"), 2048);
        File sub = new File(root, "sub");
        assertEquals(true, sub.mkdirs());
        write(new File(sub, "b.0"), 1000);
        File deeper = new File(sub, "deeper");
        assertEquals(true, deeper.mkdirs());
        write(new File(deeper, "c.0"), 5);
        assertEquals(120 + 2048 + 1000 + 5, FileSizes.sizeOf(root));
    }

    private static File write(File file, int bytes) throws IOException {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(new byte[bytes]);
        }
        return file;
    }
}
