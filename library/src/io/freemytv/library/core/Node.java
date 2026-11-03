package io.freemytv.library.core;

/** One item of a folder listing. */
public final class Node {
    public final String name;
    public final boolean dir;
    public final long size;
    public final long mtime;

    public Node(String name, boolean dir, long size, long mtime) {
        this.name = name;
        this.dir = dir;
        this.size = size;
        this.mtime = mtime;
    }
}
