package io.freemytv.library;

import io.freemytv.library.core.Node;
import io.freemytv.library.core.Source;

import jcifs.CIFSContext;
import jcifs.config.PropertyConfiguration;
import jcifs.context.BaseContext;
import jcifs.smb.NtlmPasswordAuthenticator;
import jcifs.smb.SmbFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/** The network folder on the router, over SMB 2/3 (jcifs-ng). Guest access when no login is set. */
final class SmbSource implements Source {
    private final CIFSContext context;
    private final String root;       // "smb://host/share/"

    SmbSource(String root, String user, String password) throws IOException {
        this.root = root;
        Properties p = new Properties();
        p.setProperty("jcifs.smb.client.minVersion", "SMB202");
        p.setProperty("jcifs.smb.client.maxVersion", "SMB311");
        p.setProperty("jcifs.smb.client.connTimeout", "10000");
        p.setProperty("jcifs.smb.client.responseTimeout", "20000");
        p.setProperty("jcifs.smb.client.soTimeout", "30000");
        p.setProperty("jcifs.resolveOrder", "DNS");
        p.setProperty("jcifs.smb.client.dfs.disabled", "true");
        p.setProperty("jcifs.smb.client.ipcSigningEnforced", "false");
        try {
            CIFSContext base = new BaseContext(new PropertyConfiguration(p));
            context = base.withCredentials(user.isEmpty() ? new NtlmPasswordAuthenticator(null, "guest", "") : new NtlmPasswordAuthenticator(null, user, password));
        } catch (jcifs.CIFSException e) {
            throw new IOException(e);
        }
    }

    private SmbFile file(String path, boolean dir) throws IOException {
        return new SmbFile(root + path + (dir && !path.isEmpty() ? "/" : ""), context);
    }

    @Override public List<Node> list(String path) throws IOException {
        SmbFile[] files = file(path, true).listFiles();
        List<Node> out = new ArrayList<>();
        if (files == null) return out;
        for (SmbFile f : files) {
            String name = f.getName();
            boolean dir = name.endsWith("/");
            if (dir) name = name.substring(0, name.length() - 1);
            out.add(new Node(name, dir, dir ? 0 : f.length(), f.lastModified()));
        }
        return out;
    }

    @Override public long size(String path) throws IOException {
        return file(path, false).length();
    }

    @Override public void delete(String path) throws IOException {
        if (path.isEmpty()) throw new IOException("нельзя удалить корень диска");
        SmbFile f = file(path, false);
        if (!f.exists()) return;
        if (f.isDirectory()) f = file(path, true);
        f.delete();
    }

    @SuppressWarnings("deprecation")
    @Override public InputStream open(String path, long offset) throws IOException {
        InputStream in = file(path, false).getInputStream();
        long left = offset;
        while (left > 0) {
            long n = in.skip(left);
            if (n <= 0) break;
            left -= n;
        }
        return in;
    }
}
