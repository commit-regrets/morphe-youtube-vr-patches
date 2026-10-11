package app.ytvr.extension;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.os.Bundle;

/**
 * Gives the app a task label, i.e. an app name the system can show for a running task.
 *
 * <p>Horizon OS's library names a *running* app from its own record for the package. A patched
 * build is re-signed and renamed, so there is no store record, and the library panel's
 * system-bar/wayfinder menu falls back to the placeholder string `"App name unavailable"` with the
 * Quit action next to it.
 *
 * <p>MEASURED (2026-10-10) -- this does NOT remove that placeholder. With this patch applied, `adb
 * shell dumpsys activity activities` reports `taskDescription: label="YouTube VR (patched)"` for
 * the running task, and the dock chip still shows `App name unavailable`: the panel does not read
 * the task label. Decompiling the panel
 * (`/system_ext/priv-app/LibraryPanelApp/LibraryPanelApp.apk`) shows the placeholder is drawn by
 * `com.oculus.navigator.systembar` (`KondoWayfinderMenu`) when the name in its own view-model state
 * is empty, and that state comes from the panel's library use cases, not from the task or the APK
 * label. So the label is set here because it is correct task metadata (task switches, recents) and
 * costs two instructions -- not because it fixes the card. See docs/APP-NAME-CARD.md.
 *
 * <p>Never throws into the app.
 */
public final class TaskLabel {

    private static final String TAG = "YTVR-NAME";

    private TaskLabel() {}

    public static void install(Object application) {
        try {
            if (!(application instanceof Application)) {
                log(
                        "not an Application: "
                                + (application == null ? "null" : application.getClass()));
                return;
            }
            ((Application) application)
                    .registerActivityLifecycleCallbacks(
                            new Application.ActivityLifecycleCallbacks() {
                                @Override
                                public void onActivityCreated(Activity activity, Bundle state) {
                                    apply(activity);
                                }

                                @Override
                                public void onActivityStarted(Activity activity) {}

                                @Override
                                public void onActivityResumed(Activity activity) {
                                    // Re-applied on resume: the task description is per task, and a
                                    // task can be
                                    // recreated (e.g. after the shell relaunches the app from the
                                    // library).
                                    apply(activity);
                                }

                                @Override
                                public void onActivityPaused(Activity activity) {}

                                @Override
                                public void onActivityStopped(Activity activity) {}

                                @Override
                                public void onActivitySaveInstanceState(
                                        Activity activity, Bundle state) {}

                                @Override
                                public void onActivityDestroyed(Activity activity) {}
                            });
            log("lifecycle callbacks registered (task label)");
        } catch (Throwable t) {
            log("install failed: " + t);
        }
    }

    private static void apply(Activity activity) {
        try {
            if (activity == null) {
                return;
            }
            CharSequence label =
                    activity.getApplicationInfo().loadLabel(activity.getPackageManager());
            if (label == null || label.length() == 0) {
                log("no label resource to set");
                return;
            }
            activity.setTaskDescription(
                    new ActivityManager.TaskDescription.Builder()
                            .setLabel(label.toString())
                            .build());
            log("task label set to '" + label + "' for " + activity.getClass().getSimpleName());
        } catch (Throwable t) {
            log("could not set the task label: " + t);
        }
    }

    /** Through the one gate: a build whose "Verbose logging" row is untouched prints nothing. */
    private static void log(String message) {
        VerboseLog.log(TAG, message);
    }
}
