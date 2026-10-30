package com.cosyhome.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens an app for a remote button (app_keys in cosy.properties, as keycode:package pairs), before
 * the TV's own global-key handling starts something else. On this Sony the YouTube button is
 * keycode 196, which Sony's vendor service would turn into official YouTube.
 *
 * Also closes Google's Ambient mode screensaver on DOWN. Every other key closes it, but DOWN opens
 * its content panel, which loads from the disabled Google TV home and only shows "Couldn't load
 * content".
 */
public class KeyService extends AccessibilityService {

    private static final String AMBIENT = "com.google.android.apps.tv.dreamx";

    private final SparseArray<String> keys = new SparseArray<>();

    // Ambient mode is in front: set by its window event, cleared by another app's window or any key.
    private boolean ambient;

    // The DOWN press closed Ambient mode, so its release is swallowed too.
    private boolean swallowDownRelease;

    // Purpose: read the keycode:package pairs when the service is switched on.
    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        keys.clear();
        for (String pair : getString(R.string.app_keys).split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length != 2) continue;

            // Skip a malformed keycode instead of failing the whole list.
            try {
                keys.put(Integer.parseInt(kv[0].trim()), kv[1].trim());
            } catch (NumberFormatException ignored) {
            }
        }
    }

    // Purpose: swallow both presses of a mapped button and open its app on the first press.
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (closeAmbient(event)) return true;

        String pkg = keys.get(event.getKeyCode());
        if (pkg == null) return false;

        // Launch once per press; the release is swallowed too, so the TV never sees the button.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            Intent launch = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (launch == null) launch = getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            try {
                startActivity(launch);
            } catch (ActivityNotFoundException | SecurityException e) {
                return false;
            }
        }
        return true;
    }

    // Purpose: turn DOWN in Ambient mode into BACK, which closes the screensaver like any other key.
    private boolean closeAmbient(KeyEvent event) {
        boolean down = event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN;
        if (event.getAction() == KeyEvent.ACTION_UP) {
            if (down && swallowDownRelease) {
                swallowDownRelease = false;
                return true;
            }
            return false;
        }

        // Any key press ends the screensaver, so the flag is only good for this one press.
        boolean wasAmbient = ambient;
        ambient = false;
        if (!down || !wasAmbient) return false;
        swallowDownRelease = true;
        performGlobalAction(GLOBAL_ACTION_BACK);
        return true;
    }

    // Purpose: note whether Ambient mode's window is the one in front.
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null) ambient = AMBIENT.contentEquals(pkg);
    }

    // Purpose: nothing to stop; required by AccessibilityService.
    @Override
    public void onInterrupt() {
    }
}
