package io.freemytv.library.core;

/** A folder of the library: where to look for films, and what is in it. */
public final class Folder {
    public static final String AUTO = "auto", MOVIES = "movies", SHOWS = "shows";

    public final String path;
    public final String kind;

    public Folder(String path, String kind) {
        this.path = path;
        this.kind = kind;
    }

    public String name() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }
}
