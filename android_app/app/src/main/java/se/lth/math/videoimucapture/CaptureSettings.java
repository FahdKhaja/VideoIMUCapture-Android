package se.lth.math.videoimucapture;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

public class CaptureSettings extends PreferenceFragmentCompat {
    public static final String TAG = "VIMUC-CaptureSettings";

    // How often the live stream status on this screen is refreshed. Slow enough to cost
    // nothing, fast enough that an operator standing outdoors watching for the first fix can
    // see it land without leaving the screen and coming back.
    private static final long STATUS_REFRESH_MS = 2000;

    private final Handler mStatusHandler = new Handler(Looper.getMainLooper());
    private Preference mStatusPreference;
    private final Runnable mStatusTick = new Runnable() {
        @Override
        public void run() {
            refreshStreamStatus();
            mStatusHandler.postDelayed(this, STATUS_REFRESH_MS);
        }
    };

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        Log.d(TAG, "View created -- setting up actionbar");
        super.onViewCreated(view, savedInstanceState);

        Toolbar toolbar = (Toolbar) getView().findViewById(R.id.topAppBar);

        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // back button pressed
                getActivity().getSupportFragmentManager().popBackStackImmediate();
            }
        });
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        /* This will somehow override default values we set when starting the activity.
           Therefore we set them as not persistent in the XML file.
         */
        setPreferencesFromResource(R.xml.settings, rootKey);

        CameraSettingsManager cameraSettingsManager = ((CameraCaptureActivity) getActivity()).getmCameraSettingsManager();
        cameraSettingsManager.updatePreferences(getPreferenceScreen());

        setUpSensorStreamPreferences();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Coming back from the permission prompt or from the system location settings lands
        // here, and both change the answer. Refresh immediately, then keep ticking.
        mStatusHandler.removeCallbacks(mStatusTick);
        mStatusHandler.post(mStatusTick);
    }

    @Override
    public void onPause() {
        super.onPause();
        mStatusHandler.removeCallbacks(mStatusTick);
    }

    /**
     * The two stream switches and the live status line under them.
     *
     * WHY THIS EXISTS. Both streams used to be unconditional and invisible: they registered
     * if they could and stayed silent if they could not, so a denied location permission or a
     * device with location switched off produced a clip with an empty GNSS column and nothing
     * anywhere that said why. The operator found out at the desk, days later, the same way
     * the missing-video sessions were found. So: the streams are switchable, the reason a
     * stream is not running is stated here in words, and the same facts ride along on the
     * recording readout (see CameraCaptureFragment.updateCaptureResultPanel) so they are
     * visible at the moment they can still be fixed -- before pressing record.
     */
    private void setUpSensorStreamPreferences() {
        SwitchPreferenceCompat imu = findPreference(IMUManager.PREF_IMU_ENABLED);
        SwitchPreferenceCompat gnss = findPreference(GnssLogger.PREF_GNSS_ENABLED);
        mStatusPreference = findPreference("sensor_stream_status");

        if (imu != null) {
            imu.setOnPreferenceChangeListener((preference, newValue) -> {
                // The preference stores the new value itself; the stream is restarted after
                // that has happened, hence the post.
                mStatusHandler.post(() -> applyStreamSettings());
                return true;
            });
        }

        if (gnss != null) {
            gnss.setOnPreferenceChangeListener((preference, newValue) -> {
                if (Boolean.TRUE.equals(newValue)) {
                    // Turning the switch on IS the request for the thing the permission is
                    // for, so ask for it here rather than leaving the switch on and the
                    // stream silently dead.
                    requestLocationIfNeeded();
                }
                mStatusHandler.post(() -> applyStreamSettings());
                return true;
            });
        }

        if (mStatusPreference != null) {
            mStatusPreference.setOnPreferenceClickListener(preference -> {
                onStatusClicked();
                return true;
            });
        }
        refreshStreamStatus();
    }

    /** Ask for location, or send the operator to the app settings if the prompt is gone. */
    private void requestLocationIfNeeded() {
        Activity activity = getActivity();
        if (activity == null || PermissionHelper.hasLocationPermission(activity)) {
            return;
        }
        if (PermissionHelper.locationPermanentlyDenied(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle("Location permission needed")
                    .setMessage("GNSS recording needs the location permission, and it has "
                            + "been denied to the point where Android will not ask again. "
                            + "Grant it in the app's permission settings, or leave GNSS "
                            + "recording off -- clips will simply carry no position track.")
                    .setPositiveButton("Open settings",
                            (d, w) -> PermissionHelper.launchPermissionSettings(activity))
                    .setNegativeButton("Not now", null)
                    .show();
            return;
        }
        PermissionHelper.requestLocationPermission(activity);
    }

    /**
     * The status line is a button as well as a readout: whatever is wrong, tapping it goes to
     * the place that fixes it. A status line that states a problem and offers no way out is
     * how the location permission stayed denied for the whole life of this fork.
     */
    private void onStatusClicked() {
        Activity activity = getActivity();
        CameraCaptureActivity cca = (CameraCaptureActivity) activity;
        if (cca == null) {
            return;
        }
        GnssLogger logger = cca.getmGnssLogger();
        if (!PermissionHelper.hasLocationPermission(activity)) {
            requestLocationIfNeeded();
            return;
        }
        if (logger != null && !logger.isProviderEnabled()) {
            PermissionHelper.launchLocationSettings(activity);
            return;
        }
        refreshStreamStatus();
    }

    /** Restart the streams so a flipped switch takes effect now rather than on next resume. */
    private void applyStreamSettings() {
        CameraCaptureActivity activity = (CameraCaptureActivity) getActivity();
        if (activity == null) {
            return;
        }
        if (!activity.restartSensorStreams()) {
            Toast.makeText(activity,
                    "Recording in progress -- takes effect on the next session",
                    Toast.LENGTH_SHORT).show();
        }
        refreshStreamStatus();
    }

    private void refreshStreamStatus() {
        if (mStatusPreference == null) {
            return;
        }
        CameraCaptureActivity activity = (CameraCaptureActivity) getActivity();
        if (activity == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();

        IMUManager imu = activity.getmImuManager();
        sb.append("IMU: ");
        if (imu == null) {
            sb.append("unavailable.");
        } else if (!imu.sensorsExist()) {
            sb.append("this device has no usable gyro/accel/mag set.");
        } else if (!imu.isEnabledInSettings()) {
            sb.append("off. No inertial data will be recorded.");
        } else if (!imu.isStreamActive()) {
            sb.append("idle. Starts when the capture screen is open.");
        } else {
            float hz = imu.getSensorFrequency();
            sb.append(hz > 0f
                    ? String.format(java.util.Locale.getDefault(),
                            "running, %.0f Hz delivered.", hz)
                    : "running, waiting for the first samples.");
        }

        sb.append("\n");

        GnssLogger gnss = activity.getmGnssLogger();
        sb.append("GNSS: ");
        sb.append(gnss == null ? "unavailable." : gnss.status().describe());

        mStatusPreference.setSummary(sb.toString());
    }
}
