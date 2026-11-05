package com.cosyhome.tv;

import android.content.Context;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The dock as edited on the TV (hold OK on a tile), kept in the launcher's private files (dock.txt). Same idea
 * as cleanyourtv's Order.java: the file records the build-time dock (cosy.properties `dock=`) it was made under,
 * and once that changes (a rebuild with a new dock) the saved dock is ignored, so the new one takes effect until
 * the next edit on the TV.
 *
 * Format, UTF-8 text: {@code cosyhome-dock 1}, then {@code dock <comma-separated build-time dock>}, then one
 * package name per line, in dock order.
 */
final class Dock {

    /** Seven tiles fit; the seventh already shrinks them a little. */
    static final int MAX = 7;
    private static final String FILE_NAME = "dock.txt";
    private static final String MAGIC = "cosyhome-dock 1";
    private static final String DOCK = "dock ";

    private Dock() {
    }

    /**
     * The dock to show: the saved one while it was made under this build-time dock, else the build-time one.
     * The Live TV app, when docked, always comes first; at most {@link #MAX} apps.
     */
    static List<String> load(Context ctx, List<String> configured, String liveTv) {
        List<String> saved = read(file(ctx), configured);
        return arrange(saved != null ? saved : configured, liveTv);
    }

    /** Saves the dock edited on the TV; false when the file could not be written (the edit is then not kept). */
    static boolean save(Context ctx, List<String> configured, List<String> dock) {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n')
                .append(DOCK).append(String.join(",", configured)).append('\n');
        for (String p : dock) sb.append(p).append('\n');
        AtomicFile f = new AtomicFile(file(ctx));
        FileOutputStream out = null;
        try {
            out = f.startWrite();
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            f.finishWrite(out);
            return true;
        } catch (IOException e) {
            if (out != null) f.failWrite(out);
            return false;
        }
    }

    /** Live TV first, no duplicates, at most MAX. */
    static List<String> arrange(List<String> dock, String liveTv) {
        List<String> out = new ArrayList<>();
        if (!liveTv.isEmpty() && dock.contains(liveTv)) out.add(liveTv);
        for (String p : dock) {
            if (out.size() >= MAX) break;
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }

    private static File file(Context ctx) {
        return new File(ctx.getFilesDir(), FILE_NAME);
    }

    /** The saved dock when the file is valid and was made under this build-time dock, else null (ignored, not deleted). */
    private static List<String> read(File file, List<String> configured) {
        if (!file.exists()) return null;
        String text;
        try {
            text = new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
        String[] lines = text.split("\n");
        if (lines.length < 2 || !lines[0].equals(MAGIC) || !lines[1].equals(DOCK + String.join(",", configured))) {
            return null;
        }
        List<String> dock = new ArrayList<>();
        for (int i = 2; i < lines.length; i++) {
            String p = lines[i].trim();
            if (!p.isEmpty() && !dock.contains(p)) dock.add(p);
        }
        return dock;
    }
}
