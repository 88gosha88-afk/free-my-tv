package io.freemytv.library;

import android.content.Context;
import android.content.SharedPreferences;

import io.freemytv.library.core.Folder;

import java.util.ArrayList;
import java.util.List;

/** What the person chose: the network folder, the library folders and the exclusions. */
final class Prefs {
    private Prefs() {}

    private static SharedPreferences sp;

    static void init(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("library", Context.MODE_PRIVATE);
    }

    // MARK: - The network folder

    /** "smb://192.168.1.1/Новый том/" */
    static String smbUrl() {
        String v = sp.getString("smbUrl", Config.SMB_URL).trim();
        if (!v.startsWith("smb://")) v = "smb://" + v;
        return v.endsWith("/") ? v : v + "/";
    }

    static void setSmbUrl(String v) { sp.edit().putString("smbUrl", v.trim()).apply(); }

    static String user() { return sp.getString("user", Config.SMB_USER); }
    static void setUser(String v) { sp.edit().putString("user", v.trim()).apply(); }
    static String password() { return sp.getString("password", Config.SMB_PASSWORD); }
    static void setPassword(String v) { sp.edit().putString("password", v).apply(); }

    static String tmdbKey() {
        String v = sp.getString("tmdbKey", "").trim();
        return v.isEmpty() ? Config.TMDB_KEY : v;
    }

    static void setTmdbKey(String v) { sp.edit().putString("tmdbKey", v.trim()).apply(); }

    // MARK: - Library folders

    static List<Folder> folders() {
        String saved = sp.getString("folders", null);
        List<Folder> out = new ArrayList<>();
        if (saved == null) {
            // First start: the folders named in config.properties ("!кино,Сериалы:shows,UHD,Загрузки").
            for (String part : Config.FOLDERS.split(",")) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                int colon = s.lastIndexOf(':');
                if (colon > 0 && (s.endsWith(":shows") || s.endsWith(":movies") || s.endsWith(":auto"))) out.add(new Folder(s.substring(0, colon), s.substring(colon + 1)));
                else out.add(new Folder(s, Folder.AUTO));
            }
            return out;
        }
        for (String line : saved.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", 2);
            out.add(new Folder(f[0], f.length > 1 ? f[1] : Folder.AUTO));
        }
        return out;
    }

    static void setFolders(List<Folder> folders) {
        StringBuilder b = new StringBuilder();
        for (Folder f : folders) b.append(f.path).append('\t').append(f.kind).append('\n');
        sp.edit().putString("folders", b.toString()).apply();
    }

    static List<String> excluded() {
        List<String> out = new ArrayList<>();
        for (String line : sp.getString("excluded", "").split("\n")) if (!line.isEmpty()) out.add(line);
        return out;
    }

    static void setExcluded(List<String> paths) {
        StringBuilder b = new StringBuilder();
        for (String p : paths) b.append(p).append('\n');
        sp.edit().putString("excluded", b.toString()).apply();
    }

    // MARK: - Playing

    /** "ru": the Russian soundtrack when there is one; "orig": the first one. */
    /** True: VLC is given the smb:// address and reads the disk itself; false: it gets the address of the app's own server. */
    static boolean direct() { return sp.getBoolean("direct", true); }
    static void setDirect(boolean v) { sp.edit().putBoolean("direct", v).apply(); }

    /** True: the built-in player; false: VLC (or another player) installed on the device. */
    static boolean builtin() { return sp.getBoolean("builtin", true); }
    static void setBuiltin(boolean v) { sp.edit().putBoolean("builtin", v).apply(); }

    /** Whether the main screen shows "continue watching". */
    static boolean showHistory() { return sp.getBoolean("history", true); }
    static void setShowHistory(boolean v) { sp.edit().putBoolean("history", v).apply(); }

    /** Shuffle in the player: random next episode (or film) instead of the next in order. */
    static boolean shuffle() { return sp.getBoolean("shuffle", false); }
    static void setShuffle(boolean v) { sp.edit().putBoolean("shuffle", v).apply(); }

    /** "dark", "light" or "black" (pure black, no pictures). */
    static String theme() { return sp.getString("theme", "dark"); }
    static void setTheme(String v) { sp.edit().putString("theme", v).apply(); }

    /** The background: "film" (the chosen film's picture), "aurora", "mountains", "dunes", "ocean" or "plain". */
    static String scene() { return sp.getString("scene", "film"); }
    static void setScene(String v) { sp.edit().putString("scene", v).apply(); }

    /** Seconds of picture to read ahead; 0 = automatic (more for very big files). */
    static int buffer() { return sp.getInt("buffer", 0); }
    static void setBuffer(int v) { sp.edit().putInt("buffer", v).apply(); }

    /** "auto", "hw" (force the chip's decoder) or "sw" (the processor only). */
    static String decoder() { return sp.getString("decoder", "auto"); }
    static void setDecoder(String v) { sp.edit().putString("decoder", v).apply(); }

    static String audio() { return sp.getString("audio", "ru"); }
    static void setAudio(String v) { sp.edit().putString("audio", v).apply(); }

    static long lastScan() { return sp.getLong("lastScan", 0); }
    static void setLastScan(long v) { sp.edit().putLong("lastScan", v).apply(); }
}
