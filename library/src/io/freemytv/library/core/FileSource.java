package io.freemytv.library.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** A Source over an ordinary folder (used by the tests). */
public final class FileSource implements Source {
    private final File root;

    public FileSource(File root) { this.root = root; }

    private File file(String path) { return path.isEmpty() ? root : new File(root, path); }

    @Override public List<Node> list(String path) throws IOException {
        File[] files = file(path).listFiles();
        if (files == null) throw new IOException("cannot list " + path);
        List<Node> out = new ArrayList<>();
        for (File f : files) out.add(new Node(f.getName(), f.isDirectory(), f.length(), f.lastModified()));
        return out;
    }

    @Override public long size(String path) throws IOException {
        File f = file(path);
        if (!f.isFile()) throw new IOException("no such file " + path);
        return f.length();
    }

    @Override public void delete(String path) throws IOException {
        if (path.isEmpty()) throw new IOException("cannot delete the root");
        remove(file(path));
    }

    private static void remove(File f) throws IOException {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) remove(k);
        if (f.exists() && !f.delete()) throw new IOException("cannot delete " + f.getName());
    }

    @Override public InputStream open(String path, long offset) throws IOException {
        FileInputStream in = new FileInputStream(file(path));
        long left = offset;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) break;
            left -= n;
        }
        return in;
    }
}
