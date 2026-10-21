package com.solar.launcher.sync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 2026-10-21 — Small helpers shared by NavidromeSync and BookOrbitSync.
 * Layman: read/write the little "what did I sync" notebook safely, tidy empty folders,
 * and check we're on Wi‑Fi. Tech: JSON manifest with temp-file + rename writes.
 */
public final class SyncFiles {

    private SyncFiles() {}

    /** 2026-10-21 — Manifest read; unreadable/missing = empty (sync then deletes nothing). */
    public static JSONObject readJson(File f) {
        if (f == null || !f.isFile()) return new JSONObject();
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return new JSONObject(new String(buf, 0, off, "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        } finally {
            if (in != null) try { in.close(); } catch (Exception ignored) {}
        }
    }

    /** 2026-10-21 — Write via temp file + rename so a power cut can't leave a half manifest. */
    public static void writeJsonAtomic(File f, JSONObject o) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            out.write(o.toString().getBytes("UTF-8"));
            out.getFD().sync();
        } finally {
            out.close();
        }
        if (f.exists()) f.delete();
        if (!tmp.renameTo(f)) throw new Exception("Manifest write failed");
    }

    /** 2026-10-21 — Path relative to root, for manifests that survive mount-path changes. */
    public static String relative(File root, File f) {
        String r = root.getAbsolutePath();
        String p = f.getAbsolutePath();
        return p.startsWith(r + File.separator) ? p.substring(r.length() + 1) : p;
    }

    /** 2026-10-21 — Delete now-empty folders upward, stopping at (and keeping) stopAt. */
    public static void pruneEmptyDirs(File dir, File stopAt) {
        while (dir != null && !dir.equals(stopAt)) {
            String[] left = dir.list();
            if (left == null || left.length > 0 || !dir.delete()) return;
            dir = dir.getParentFile();
        }
    }

    public static List<String> keys(JSONObject o) {
        List<String> out = new ArrayList<String>();
        if (o == null) return out;
        Iterator<String> it = o.keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }

    /** 2026-10-21 — Auto-sync only on Wi‑Fi (no surprise downloads on tethering). */
    public static boolean onWifi(Context ctx) {
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            return ni != null && ni.isConnected() && ni.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }
}
