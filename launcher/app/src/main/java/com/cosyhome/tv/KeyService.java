package com.cosyhome.tv;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 *
 * With power_on_home=true, Home comes up each time the TV is switched on, whatever the TV brought back (the last
 * app or HDMI input), unless a key is pressed first or something is already playing sound (a cast that woke the
 * TV). Same idea as cleanyourtv's power_on_home_always.
 */
public class KeyService extends AccessibilityService {

    private static final String AMBIENT = "com.google.android.apps.tv.dreamx";

    private final SparseArray<String> keys = new SparseArray<>();

    // Ambient mode is in front: set by its window event, cleared by another app's window or any key.
    private boolean ambient;

    // The DOWN press closed Ambient mode, so its release is swallowed too.
    private boolean swallowDownRelease;

    /** After the screen comes on, how long the TV gets to bring back its last app or input before Home. */
    private static final long POWER_ON_DELAY_MS = 1500L;
    /** A service bound this soon after boot counts as a power-on too. */
    private static final long BOOT_WINDOW_MS = 3 * 60_000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable homeAtPowerOn = this::homeAtPowerOn;
    private BroadcastReceiver screenOn;
    // Armed at power-on, disarmed by the first key: from then on the TV is the user's.
    private boolean powerOnArmed;

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
        setUpPowerOnHome();
    }

    // Purpose: with power_on_home=true, arm Home for every screen-on, and for this boot when the service binds early.
    private void setUpPowerOnHome() {
        if (!"true".equalsIgnoreCase(getString(R.string.power_on_home).trim())) return;
        if (screenOn == null) {
            screenOn = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    armPowerOnHome();
                }
            };
            registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        }
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) armPowerOnHome();
    }

    private void armPowerOnHome() {
        powerOnArmed = true;
        handler.removeCallbacks(homeAtPowerOn);
        handler.postDelayed(homeAtPowerOn, POWER_ON_DELAY_MS);
    }

    // Purpose: open Home, unless a key was pressed since power-on or sound is already playing.
    private void homeAtPowerOn() {
        if (!powerOnArmed) return;
        powerOnArmed = false;
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio != null && audio.isMusicActive()) return;
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (ActivityNotFoundException | SecurityException ignored) {
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(homeAtPowerOn);
        if (screenOn != null) unregisterReceiver(screenOn);
        screenOn = null;
        super.onDestroy();
    }

    // Purpose: swallow both presses of a mapped button and open its app on the first press.
    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        // The first key after power-on (not the power key that woke the TV) hands the TV to the user.
        if (powerOnArmed && event.getAction() == KeyEvent.ACTION_DOWN
                && event.getKeyCode() != KeyEvent.KEYCODE_POWER && event.getKeyCode() != KeyEvent.KEYCODE_WAKEUP
                && event.getKeyCode() != KeyEvent.KEYCODE_TV_POWER) {
            powerOnArmed = false;
            handler.removeCallbacks(homeAtPowerOn);
        }
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
