package io.freemytv.library.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** Where the films are kept: a network folder (SMB) or, in tests, a folder on the disk. Paths are "a/b/c" from the share's root, "" is the root. */
public interface Source {
    List<Node> list(String path) throws IOException;

    long size(String path) throws IOException;

    /** Deletes a file, or a folder with everything in it. Needs write access to the share. */
    void delete(String path) throws IOException;

    /** The file's bytes from `offset` on; the caller closes the stream. */
    InputStream open(String path, long offset) throws IOException;
}
