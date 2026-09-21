package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import androidx.annotation.RequiresApi;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class CameraSettingsManager {
    private static final String TAG = "CameraSettingsManager";
    private enum Setting {OIS, OIS_DATA, DVS, DISTORTION_CORRECTION, RAW_PIXELS, VIDEO_SIZE, FOCUS_MODE, EXPOSURE_MODE, ZOOM_RATIO, PHYSICAL_CAMERA};
    private Map<Setting, CameraSetting> mCameraSettings;
    private boolean mInitialized = false;

    public CameraSettingsManager(Activity activity) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(activity);
        CameraSetting.setSharedPreferences(preferences);
        CameraSetting.setActivity(activity);
        //CameraSetting.setRestoreDefault(); // For DEBUG
        mCameraSettings = new HashMap<>();
    }

    public void updateRequestBuilder(CaptureRequest.Builder builder) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            entry.getValue().updateCaptureRequest(builder);
        }
    }

    public void updateOutputConfiguration(OutputConfiguration outputConfiguration) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            entry.getValue().updateOutputConfiguration(outputConfiguration);
        }
    }

    public void updatePreferences(PreferenceScreen preferenceScreen) {
        for (Map.Entry<Setting, CameraSetting> entry : mCameraSettings.entrySet()) {
            CameraSetting cameraSetting = entry.getValue();
            cameraSetting.updatePreferenceScreen(preferenceScreen);
        }
    }

    public void updateSettings(CameraCharacteristics cameraCharacteristics) {
        if (mInitialized) {
            return;
        }
        mCameraSettings.put(Setting.OIS,
                new CameraSettingBoolean(
                        "ois",
                        cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION),
                        CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        false
                )
        );

        if (Build.VERSION.SDK_INT >= 28) {
            int[] oisDataModes = cameraCharacteristics.get(
                    CameraCharacteristics.STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES);
            if (oisDataModes == null) {
                // The device advertises no OIS data modes -- which is what this phone does, and
                // is why OIS_samples has been empty in every clip this fork has recorded (#41).
                // "Not advertised" and "not supported" are different claims, and a vendor HAL
                // that omits the characteristic may still honour the request key. So ask anyway,
                // and let the recorded ois_data_mode (proto field 29) and the sample count say
                // which it was. The request is set inside a try/catch, so a HAL that rejects the
                // key costs a log line, not a session.
                Log.i(TAG, "STATISTICS_INFO_AVAILABLE_OIS_DATA_MODES is null; requesting OIS "
                        + "data anyway -- the recorded ois_data_mode will say whether it took");
                oisDataModes = new int[]{CameraMetadata.STATISTICS_OIS_DATA_MODE_OFF,
                        CameraMetadata.STATISTICS_OIS_DATA_MODE_ON};
            }
            mCameraSettings.put(Setting.OIS_DATA,
                    new CameraSettingBoolean(
                            "ois_data",
                            oisDataModes,
                            CameraMetadata.STATISTICS_OIS_DATA_MODE_ON,
                            CaptureRequest.STATISTICS_OIS_DATA_MODE,
                            false
                )
            );
        } else {
            mCameraSettings.put(Setting.OIS_DATA,
                    new CameraSettingBoolean("ois_data", null, 1, null, false)
            );
        }

        mCameraSettings.put(Setting.DVS,
                new CameraSettingBoolean(
                        "dvs",
                        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES),
                        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        false
                )
        );

        if (Build.VERSION.SDK_INT >= 28) {
            mCameraSettings.put(Setting.DISTORTION_CORRECTION,
                    new CameraSettingBoolean(
                            "distortion_correction",
                            cameraCharacteristics.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES),
                            CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
                            CameraMetadata.DISTORTION_CORRECTION_MODE_OFF,
                            CaptureRequest.DISTORTION_CORRECTION_MODE,
                            false
                    )
            );
        } else {
            // Was a second OIS_DATA entry, which silently overwrote the one built above on any
            // pre-28 device. Distortion correction is the setting this branch is standing in for.
            mCameraSettings.put(Setting.DISTORTION_CORRECTION,
                    new CameraSettingBoolean("distortion_correction", null, 1, null, false)
            );
        }

        mCameraSettings.put(Setting.RAW_PIXELS, new CameraSettingRawPixels(cameraCharacteristics));

        mCameraSettings.put(Setting.VIDEO_SIZE, new CameraSettingVideoSize(cameraCharacteristics));
        mCameraSettings.put(Setting.FOCUS_MODE, new CameraSettingFocusMode(cameraCharacteristics));
        mCameraSettings.put(Setting.EXPOSURE_MODE, new CameraSettingExposureMode(cameraCharacteristics));
        mCameraSettings.put(Setting.ZOOM_RATIO, new CameraSettingZoomRatio(cameraCharacteristics));
        mCameraSettings.put(Setting.PHYSICAL_CAMERA, new CameraSettingPhysicalCamera(cameraCharacteristics));

        mInitialized = true;

    }

    public Boolean isInitialized() {
        return mInitialized;
    }

    public Boolean OISEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.OIS)).isOn();
    }

    public Boolean DVSEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.DVS)).isOn();
    }

    public Boolean OISDataEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.OIS_DATA)).isOn();
    }

    public Boolean DistortionCorrectionEnabled() {
        return ((CameraSettingBoolean) mCameraSettings.get(Setting.DISTORTION_CORRECTION)).isOn();
    }

    public Size getVideoSize() {
        return ((CameraSettingVideoSize) mCameraSettings.get(Setting.VIDEO_SIZE)).getSize();
    }

    public Boolean focusOnTouch() {
        return ((CameraSettingFocusMode) mCameraSettings.get(Setting.FOCUS_MODE)).getMode()
                == CameraSettingFocusMode.FocusMode.TOUCH_AUTO;
    }

    public Boolean exposureOnTouch() {
        return ((CameraSettingExposureMode) mCameraSettings.get(Setting.EXPOSURE_MODE)).getMode()
                == CameraSettingExposureMode.Mode.TOUCH_AUTO;
    }
    
}

abstract class CameraSetting {
    protected Boolean mConfigurable;
    protected CaptureRequest.Key mRequestKey;
    protected static SharedPreferences mSharedPreferences;
    protected static Activity mActivity;
    protected static boolean mRestoreDefault = false;
    protected String mPrefKey;

    public static void setSharedPreferences(SharedPreferences preferences) {mSharedPreferences=preferences;};
    public static void setActivity(Activity activity) {mActivity=activity;};
    public static void setRestoreDefault() {mRestoreDefault=true;};

    public void updatePreferenceScreen(PreferenceScreen screen) {
        Log.d("Settingsmanager", mPrefKey);
        Preference pref = screen.findPreference(mPrefKey);
        if (pref != null) {
            updatePreference(pref);
        } else {
            throw new RuntimeException("Preference not found: " + mPrefKey);
        }
    }

    protected void updatePreference(Preference preference) {

        preference.setEnabled(mConfigurable);
        preference.setPersistent(true);
    }
    public void updateCaptureRequest(CaptureRequest.Builder builder) {}
    public void updateOutputConfiguration(OutputConfiguration outputConfiguration) {}

}

//Camera setting default is handled through the Preference XML file.
class CameraSettingBoolean extends CameraSetting {
    private int mOnValue, mOffValue;
    private Boolean mDefaultOn, mRequestable;

    public CameraSettingBoolean(String prefKey,
                                int[] modes,
                                int onValue,
                                CaptureRequest.Key requestKey,
                                Boolean defaultOn) {
        this(prefKey, modes, onValue, 1-onValue, requestKey, defaultOn);

    }

    public CameraSettingBoolean(String prefKey,
                                int[] modes,
                                int onValue,
                                int offValue,
                                CaptureRequest.Key requestKey,
                                Boolean defaultOn) {
        mPrefKey = prefKey;
        mRequestKey = requestKey;
        mOnValue = onValue;
        mOffValue = offValue;
        boolean forceDefault;

        boolean offAvailable = false;
        boolean onAvailable = false;
        if (modes != null) {
            for (int m : modes) {
                offAvailable |= (m == offValue);
                onAvailable |= (m == onValue);
            }
        }

        //Figure out valid default value
        if (offAvailable && onAvailable) {
            mDefaultOn = defaultOn;
            mConfigurable = true;
            mRequestable = true;
            forceDefault = mRestoreDefault;
        } else if (!offAvailable && !onAvailable) {
            mDefaultOn = false;
            mConfigurable = false;
            mRequestable = false;
            forceDefault = true;
        } else {
            mDefaultOn = onAvailable;
            mConfigurable = false;
            mRequestable = true;
            forceDefault = true;
        }
        //Set default if not present
        if (!mSharedPreferences.contains(prefKey) || forceDefault) {
            mSharedPreferences.edit().putBoolean(prefKey, mDefaultOn).apply();
        }
    }

    public Boolean isOn() {
        return mSharedPreferences.getBoolean(mPrefKey, mDefaultOn);
    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        if (!mRequestable) {
            return;
        }
        // A key may be requested that the device never advertised (OIS data, #44): setting it is
        // how we find out whether the HAL honours it. One rejected key must not cost the session.
        try {
            builder.set(mRequestKey, isOn() ? mOnValue : mOffValue);
        } catch (IllegalArgumentException e) {
            Log.w("CameraSetting", "capture request rejected key for '" + mPrefKey
                    + "': " + e.getMessage());
        }
    }

    @Override
    protected void updatePreference(Preference preference) {

        ((SwitchPreferenceCompat) preference).setChecked(isOn());
        super.updatePreference(preference);
    }
}

class CameraSettingVideoSize extends CameraSetting {

    private List<Size> mValidSizes;
    private static Size DEFAULT_VIDEO_SIZE = new Size(1280, 960);
    private final String mSizePrefKey = "video_size";
    private final String mMaxSensorPrefKey = "use_full_sensor";
    private final boolean DEFAULT_MAX_SENSOR = true;
    private Rect mArraySensorSize;



    public CameraSettingVideoSize(CameraCharacteristics cameraCharacteristics) {

        mArraySensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

        // Update Sizes
        StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics
                .SCALER_STREAM_CONFIGURATION_MAP);
        mConfigurable = (map != null);

        if (mConfigurable) {
            mValidSizes = Arrays.asList(map.getOutputSizes(MediaRecorder.class));

            // Check if valid, find closest size if not.
            Size currentSize = getSize();
            if (!mValidSizes.contains(currentSize)) {
                Size newSize = CameraUtils.chooseOptimalSize(mValidSizes.toArray(new Size[0]),
                        currentSize.getWidth(), currentSize.getHeight(), currentSize);
                setSize(newSize);
            }
        }

        //Set default
        if (mRestoreDefault || !mSharedPreferences.contains(mSizePrefKey)) {
            setSize(DEFAULT_VIDEO_SIZE);
        }
        if (mRestoreDefault || !mSharedPreferences.contains(mMaxSensorPrefKey)) {
            mSharedPreferences.edit().putBoolean(mMaxSensorPrefKey, DEFAULT_MAX_SENSOR).apply();
        }
    }

    public Size getSize() {
        return Size.parseSize(mSharedPreferences.getString(mSizePrefKey, DEFAULT_VIDEO_SIZE.toString()));
    }

    private void setSize(Size size) {
        mSharedPreferences.edit().putString(mSizePrefKey, size.toString()).apply();
    }

    private boolean getMaxSensor() {
        return mSharedPreferences.getBoolean(mMaxSensorPrefKey, DEFAULT_MAX_SENSOR);
    }

    private List<Size> getSensorSizes() {
        return mValidSizes.stream()
                .filter(s -> s.getWidth() == s.getHeight() * mArraySensorSize.width() / mArraySensorSize.height())
                .collect(Collectors.toList());
    }

    public void updatePreferenceScreen(PreferenceScreen screen) {
        ListPreference listPreference = screen.findPreference(mSizePrefKey);
        listPreference.setEnabled(mConfigurable);
        if (mConfigurable) {
            updatePreferenceList(listPreference, getMaxSensor());
            listPreference.setPersistent(true);
        }

        CheckBoxPreference maxSensorPref = screen.findPreference(mMaxSensorPrefKey);
        maxSensorPref.setEnabled(mConfigurable);
        maxSensorPref.setChecked(getMaxSensor());
        maxSensorPref.setPersistent(true);
        maxSensorPref.setOnPreferenceChangeListener((preference, newValue) -> {
            updatePreferenceList(listPreference, (boolean) newValue);
            return true;
        });
    }

    private void updatePreferenceList(ListPreference listPreference, boolean useSensorAspectRatio) {
        List<Size> validSizes = useSensorAspectRatio ? getSensorSizes() : mValidSizes;

        if (validSizes.isEmpty()) {
            listPreference.setEnabled(false);
            return;
        }

        String[] stringSizes = validSizes.stream().map(Object::toString).toArray(String[]::new);
        int defaultIndex = validSizes.indexOf(getSize());

        // The previous choice was from the full list, this size is not present in the sensor aspect ratio list.
        if (defaultIndex == -1) {
            Size closestSize = CameraUtils.chooseOptimalSize(
                    validSizes.toArray(new Size[0]),
                    getSize().getWidth(),
                    getSize().getHeight(),
                    new Size(mArraySensorSize.width(), mArraySensorSize.height()));
            defaultIndex = validSizes.indexOf(closestSize);
        }

        listPreference.setEntryValues(stringSizes);
        listPreference.setEntries(stringSizes);
        listPreference.setValueIndex(defaultIndex);
        listPreference.setEnabled(true);
    }
}

// If logical camera, enable choosing a specific lens.
class CameraSettingPhysicalCamera extends CameraSetting {

    private List<String> mCameraIds;
    private List<String> mCameraIdsDesc;
    private static String LOGICAL_ID = "Logical"; //Means the logical device

    public CameraSettingPhysicalCamera(CameraCharacteristics cameraCharacteristics) {
        mPrefKey = "camera_id";
        //Check physical stats
        int[] capabilities = cameraCharacteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean multi_camera = false;
        for (int cap :  capabilities) {
            if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) {
                multi_camera = true;
                break;
            }
        }
        if (multi_camera && Build.VERSION.SDK_INT >=28) {
            List<String> physicalCameraIds =  new ArrayList<>(cameraCharacteristics.getPhysicalCameraIds());
            Collections.sort(physicalCameraIds);
            mCameraIds = new LinkedList<>();
            mCameraIds.add(LOGICAL_ID);
            mCameraIds.addAll(physicalCameraIds);
            mCameraIdsDesc = new LinkedList<>();
            mCameraIdsDesc.add(LOGICAL_ID);
            for (String id : physicalCameraIds) {
                mCameraIdsDesc.add("Physical ID " + id);
            }
            mConfigurable = true;
        } else {
            mConfigurable = false;
        }

        //Set default
        if (mRestoreDefault || !mSharedPreferences.contains(mPrefKey)) {
            mSharedPreferences.edit().putString(mPrefKey, LOGICAL_ID).apply();
        }
    }

    public String getId() {
        return mSharedPreferences.getString(mPrefKey, LOGICAL_ID);
    }

    public void updatePreference(Preference pref) {
        ListPreference idPref = (ListPreference)pref;
        if (mConfigurable) {
            idPref.setEntryValues(mCameraIds.toArray(new String[0]));
            idPref.setEntries(mCameraIdsDesc.toArray(new String[0]));
            idPref.setValueIndex(mCameraIds.indexOf(getId()));
            idPref.setVisible(true);
            super.updatePreference(pref);
        } else {
            idPref.setVisible(false);
        }
    }

    public void updateOutputConfiguration(OutputConfiguration outputConfiguration) {
        String id = getId();
        if (!id.equals(LOGICAL_ID) && Build.VERSION.SDK_INT >= 28) {
            outputConfiguration.setPhysicalCameraId(id);
        }
    }
}

class CameraSettingFocusMode extends CameraSetting {

    enum FocusMode {CONTINUOUS_AUTO, TOUCH_AUTO, MANUAL}
    private List<FocusMode> mValidModes = new ArrayList<>();
    private final FocusMode DEFAULT_FOCUS_MODE = FocusMode.TOUCH_AUTO;
    private final float DEFAULT_FOCUS_DISTANCE = 0;
    private final float FOCUS_RESOLUTION = 0.1f;
    private Float mMinFocusDistance;
    private final String mModePrefKey = "focus_mode";
    private final String mDistancePrefKey = "focus_distance";
    private final String mHyperfocalPrefKey = "focus_hyperfocal";
    /** Computed once from the lens; null when the device does not publish enough to compute. */
    private Float mHyperfocalDiopters;

    public CameraSettingFocusMode(CameraCharacteristics cameraCharacteristics) {
        //Check available options
        int[] availableModes = cameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        for (int m : availableModes) {
            switch (m) {
                case CameraCharacteristics.CONTROL_AF_MODE_OFF:
                    mValidModes.add(FocusMode.MANUAL);
                    break;
                case CameraCharacteristics.CONTROL_AF_MODE_AUTO:
                    mValidModes.add(FocusMode.TOUCH_AUTO);
                    break;
                case CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO:
                    mValidModes.add(FocusMode.CONTINUOUS_AUTO);
                    break;
            }
        }
        Collections.sort(mValidModes);

        //Check valid range
        mMinFocusDistance = cameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);

        mHyperfocalDiopters = computeHyperfocalDiopters(cameraCharacteristics);
        if (mHyperfocalDiopters != null && mMinFocusDistance != null) {
            // The seekbar's max is the CLOSEST the lens can focus, in diopters. Hyperfocal is
            // always further away than that, so it should sit comfortably inside the range --
            // but clamp rather than trust, because a request outside the range is undefined.
            mHyperfocalDiopters = Math.max(0f, Math.min(mHyperfocalDiopters, mMinFocusDistance));
            Log.i("CameraSetting", String.format(Locale.US,
                    "hyperfocal %.3f diopters (%.2f m); lens closest %.3f diopters",
                    mHyperfocalDiopters, 1.0 / mHyperfocalDiopters, mMinFocusDistance));
        }

        //Set default
        if (mRestoreDefault || !mSharedPreferences.contains(mModePrefKey)) {
            mSharedPreferences.edit().putString(mModePrefKey, DEFAULT_FOCUS_MODE.toString()).apply();
        }
        if (mRestoreDefault || !mSharedPreferences.contains(mDistancePrefKey)) {
            mSharedPreferences.edit().putFloat(mDistancePrefKey, DEFAULT_FOCUS_DISTANCE).apply();
        }
    }

    public FocusMode getMode() {
        return FocusMode.valueOf(getModeString());
    }

    private String getModeString() {
        return mSharedPreferences.getString(mModePrefKey, DEFAULT_FOCUS_MODE.toString());
    }

    private float getFocusDistance() {
        if (mHyperfocalDiopters != null
                && mSharedPreferences.getBoolean(mHyperfocalPrefKey, false)) {
            return mHyperfocalDiopters;
        }
        return mSharedPreferences.getFloat(mDistancePrefKey, DEFAULT_FOCUS_DISTANCE);
    }

    /**
     * The hyperfocal distance in DIOPTERS, which is what LENS_FOCUS_DISTANCE takes.
     *
     * ReconStab #61. MANUAL focus has existed here all along, but the operator had to supply
     * a diopter, which meant the one focus setting worth using for a walk -- focus once at
     * hyperfocal, never move the voice coil again -- was a number nobody could produce in the
     * field. Continuous AF hunts, a moving voice coil breathes the focal length by a few
     * percent across a session, and a solve run with freeze_intrinsics is then asserting a
     * constant that was not constant.
     *
     * H = f^2 / (N * c) + f, everything in millimetres: focal length, f-number, and the
     * circle of confusion taken as the sensor diagonal over 1500 -- the standard convention,
     * and conservative for a matcher, which cares about a sharp gradient rather than about
     * what looks acceptable in a print.
     *
     * Note what this does NOT settle. Step 1 of the issue is free and has not been run: plot
     * lens_intrinsic_calibration[0] across an AF-on walk already on disk and count the frames
     * whose lens_state says the lens was still moving when the shutter opened. If the focal is
     * flat, none of this is needed. This computes the number so that the experiment can be
     * shot when the plot asks for it, and changes nothing by default.
     */
    private static Float computeHyperfocalDiopters(CameraCharacteristics ch) {
        float[] focals = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float[] apertures = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES);
        SizeF sensor = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (focals == null || focals.length == 0 || apertures == null || apertures.length == 0
                || sensor == null) {
            return null;
        }
        double diagonalMm = Math.sqrt(sensor.getWidth() * sensor.getWidth()
                + sensor.getHeight() * sensor.getHeight());
        return hyperfocalDiopters(focals[0], apertures[0], diagonalMm);
    }

    /**
     * The arithmetic on its own, so it can be checked against numbers worked out by hand
     * rather than only against whatever the device happens to report.
     *
     * @return diopters (1/m), or null if any input makes the formula meaningless.
     */
    static Float hyperfocalDiopters(float focalMm, float fNumber, double diagonalMm) {
        double c = diagonalMm / 1500.0;
        if (focalMm <= 0 || fNumber <= 0 || c <= 0) {
            return null;
        }
        double hMm = (focalMm * focalMm) / (fNumber * c) + focalMm;
        if (hMm <= 0) {
            return null;
        }
        // Diopters are 1/metre, and H is in millimetres.
        return (float) (1000.0 / hMm);
    }

    public void updatePreferenceScreen(PreferenceScreen prefScreen) {
        ListPreference modePref = prefScreen.findPreference(mModePrefKey);

        if (modePref != null) {
            String[] focusModeEnum = new String[mValidModes.size()];
            String[] focusModeDesc = new String[mValidModes.size()];
            String[] allFocusModeDesc = mActivity.getResources().getStringArray(R.array.focus_mode_desc);
            for (int i = 0; i < mValidModes.size() ; i++) {
                FocusMode m = mValidModes.get(i);
                String mString = m.toString();
                focusModeEnum[i] = mString;
                focusModeDesc[i] = allFocusModeDesc[m.ordinal()];
            }
            modePref.setEntries(focusModeDesc);
            modePref.setEntryValues(focusModeEnum);
            modePref.setValue(getModeString());
            modePref.setPersistent(true);
        }


        FloatSeekBarPreference distPref = prefScreen.findPreference(mDistancePrefKey);
        if (mMinFocusDistance != null && mValidModes.contains(FocusMode.MANUAL)) {
            distPref.setMax(mMinFocusDistance);
            distPref.setMin(0);
            distPref.setResolution(FOCUS_RESOLUTION);
            distPref.setValue(DEFAULT_FOCUS_DISTANCE);
            distPref.setPersistent(true);
            distPref.setEnabled(getMode() == FocusMode.MANUAL);

            modePref.setOnPreferenceChangeListener((preference, newValue) -> {
                distPref.setEnabled(FocusMode.valueOf((String) newValue) == FocusMode.MANUAL);
                return true;
            });
        } else {
            distPref.setVisible(false);
        }

    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        switch (getMode()) {
            case MANUAL:
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF);
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, getFocusDistance());
                break;
            case TOUCH_AUTO:
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, getFocusDistance());
                break;
            case CONTINUOUS_AUTO:
                builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                builder.set(CaptureRequest.CONTROL_AF_REGIONS, null);
                break;
        }
    }
}

class CameraSettingExposureMode extends CameraSetting {

    enum Mode {CONTINUOUS_AUTO, TOUCH_AUTO, MANUAL}
    private List<Mode> mValidModes = new ArrayList<>();
    private final Mode DEFAULT_MODE = Mode.TOUCH_AUTO;
    private float DEFAULT_EXPOSURE_MS = 5f;
    private float MAX_EXPOSURE_MS =30f; // 30ms, More is not feasible given 30 fps requirement.
    private int DEFAULT_ISO =200;
    private int MAX_ISO =2000; // Much more does not make sense
    private final float EXPOSURE_RESOLUTION = 1e-2f;
    private Range<Float> mExposureTimeRange = null;
    private Range<Integer> mISORange = null;
    private final String mModePrefKey = "exposure_mode";
    private final String mISOPrefKey = "iso";
    private final String mExposurePrefKey = "exposure";

    public CameraSettingExposureMode(CameraCharacteristics cameraCharacteristics) {
        //Check available modes
        int[] availableModes = cameraCharacteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        for (int m : availableModes) {
            switch (m) {
                case CameraCharacteristics.CONTROL_AE_MODE_OFF:
                    mValidModes.add(Mode.MANUAL);
                    break;
                case CameraCharacteristics.CONTROL_AE_MODE_ON:
                    mValidModes.add(Mode.TOUCH_AUTO);
                    mValidModes.add(Mode.CONTINUOUS_AUTO);
                    break;
            }
        }
        Collections.sort(mValidModes);

        //Set default value
        if (mRestoreDefault || !mSharedPreferences.contains(mModePrefKey)) {
            mSharedPreferences.edit().putString(mModePrefKey, DEFAULT_MODE.toString()).apply();
        }

        // If Mode OFF is supported, check ranges.
        if (mValidModes.contains(Mode.MANUAL)) {

            //Reduce ISO range and check default
            mISORange = cameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            mISORange = mISORange.intersect(mISORange.getLower(), MAX_ISO);
            DEFAULT_ISO = mISORange.clamp(DEFAULT_ISO);

            //Reduce Exposure range and check default
            Range<Long> exposureRangeNs = cameraCharacteristics.get(
                    CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            exposureRangeNs = exposureRangeNs.intersect(exposureRangeNs.getLower(),
                    exposureMsToNs(MAX_EXPOSURE_MS));
            mExposureTimeRange = new Range<>(
                    exposureNsToMs(exposureRangeNs.getLower()),
                    exposureNsToMs(exposureRangeNs.getUpper())
            );
            DEFAULT_EXPOSURE_MS = mExposureTimeRange.clamp(DEFAULT_EXPOSURE_MS);

            //Set default values
            if (mRestoreDefault || !mSharedPreferences.contains(mISOPrefKey)) {
                mSharedPreferences.edit().putInt(mISOPrefKey, DEFAULT_ISO).apply();
            }
            if (mRestoreDefault || !mSharedPreferences.contains(mExposurePrefKey)) {
                mSharedPreferences.edit().putFloat(mExposurePrefKey, DEFAULT_EXPOSURE_MS).apply();
            }
        }
    }

    private float exposureNsToMs(long expNs) {
        return expNs*1e-6f;
    }
    private long exposureMsToNs(float expMs) {
        return (long) (expMs*1e6f);
    }



    public Mode getMode() {
        Mode mode;
        try {
             mode = Mode.valueOf(getModeString());
        } catch (IllegalArgumentException e) {
            // Stored mode is no longer valid, go to default.
            mSharedPreferences.edit().putString(mModePrefKey, DEFAULT_MODE.toString()).apply();
            mode = DEFAULT_MODE;
        }
        return mode;
    }

    private String getModeString() {
        return mSharedPreferences.getString(mModePrefKey, DEFAULT_MODE.toString());
    }

    private int getISO() {
        return mSharedPreferences.getInt(mISOPrefKey, DEFAULT_ISO);
    }

    private long getExposureNs() {
        return exposureMsToNs(getExposureMs());
    }

    private float getExposureMs() {
        return mSharedPreferences.getFloat(mExposurePrefKey, DEFAULT_EXPOSURE_MS);
    }

    public void updatePreferenceScreen(PreferenceScreen prefScreen) {

        ListPreference modePref = prefScreen.findPreference(mModePrefKey);

        if (modePref != null) {
            String[] ModeEnum = new String[mValidModes.size()];
            String[] ModeDesc = new String[mValidModes.size()];
            String[] allModeDesc = mActivity.getResources().getStringArray(R.array.exposure_mode_desc);
            for (int i = 0; i < mValidModes.size() ; i++) {
                Mode m = mValidModes.get(i);
                String mString = m.toString();
                ModeEnum[i] = mString;
                ModeDesc[i] = allModeDesc[m.ordinal()];
            }
            modePref.setEntries(ModeDesc);
            modePref.setEntryValues(ModeEnum);
            modePref.setValue(getModeString());
            modePref.setPersistent(true);
        }


        FloatSeekBarPreference expPref = prefScreen.findPreference(mExposurePrefKey);
        SeekBarPreference isoPref = prefScreen.findPreference(mISOPrefKey);
        if (mValidModes.contains(Mode.MANUAL)) {
            expPref.setMax(mExposureTimeRange.getUpper());
            expPref.setMin(mExposureTimeRange.getLower());
            expPref.setResolution(EXPOSURE_RESOLUTION);
            expPref.setValue(getExposureMs());
            expPref.setPersistent(true);
            expPref.setEnabled(getMode() == Mode.MANUAL);

            isoPref.setMax(mISORange.getUpper());
            isoPref.setMin(mISORange.getLower());
            isoPref.setValue(getISO());
            isoPref.setPersistent(true);
            isoPref.setEnabled(getMode() == Mode.MANUAL);

            modePref.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean enable = Mode.valueOf((String) newValue) == Mode.MANUAL;
                expPref.setEnabled(enable);
                isoPref.setEnabled(enable);
                return true;
            });
        } else {
            expPref.setVisible(false);
            isoPref.setVisible(false);
            modePref.setEnabled(false);
        }
    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        switch (getMode()) {
            case MANUAL:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, getExposureNs());
                builder.set(CaptureRequest.SENSOR_SENSITIVITY, getISO());
                break;
            case TOUCH_AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.CONTROL_AE_LOCK, true);
                break;
            case CONTINUOUS_AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.CONTROL_AE_REGIONS, null);
                // Say the quiet part. A CaptureRequest.Builder is long-lived and remembers
                // every key ever set on it, so "continuous auto" that never clears AE_LOCK
                // inherits a lock set by a stills burst, a touch-to-expose, or the TOUCH_AUTO
                // branch above -- which used to fall straight through into this one for want
                // of a break. Continuous means continuous.
                builder.set(CaptureRequest.CONTROL_AE_LOCK, false);
                break;
        }
    }
}

class CameraSettingZoomRatio extends CameraSetting {
    private final float DEFAULT_ZOOM_RATIO = 1.0f;
    private final float ZOOM_RESOLUTION = 0.1f;
    private Range<Float> mZoomRange;

    public CameraSettingZoomRatio(CameraCharacteristics cameraCharacteristics) {
        mPrefKey = "zoom_ratio";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            mZoomRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            mConfigurable = true;
        } else {
            mConfigurable = false;
        }

        //Set default if not present
        if (!mSharedPreferences.contains(mPrefKey) || mRestoreDefault) {
            mSharedPreferences.edit().putFloat(mPrefKey, DEFAULT_ZOOM_RATIO).apply();
        }
    }

    private float getRatio() {
        return mSharedPreferences.getFloat(mPrefKey, DEFAULT_ZOOM_RATIO);
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        if (mConfigurable) {
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, getRatio());
        }
    }

    @Override
    protected void updatePreference(Preference preference) {
        FloatSeekBarPreference seekbarPref = (FloatSeekBarPreference)preference;
        if(mConfigurable) {
            seekbarPref.setVisible(true);
            seekbarPref.setMax(mZoomRange.getUpper());
            seekbarPref.setMin(mZoomRange.getLower());
            seekbarPref.setResolution(ZOOM_RESOLUTION);
            seekbarPref.setValue(getRatio());
            super.updatePreference(preference);
        } else {
            seekbarPref.setVisible(false);
        }
    }
}

/**
 * Turn off what the HAL does to the pixels for a human viewer (ReconStab #55).
 *
 * EDGE_MODE and NOISE_REDUCTION_MODE appear nowhere else in this fork, so every solve frame
 * ever shot here ran the vendor default for a video stream: spatial denoise and edge
 * enhancement, tuned to look good to a person.
 *
 * Both are hostile to matching, in opposite directions. Sharpening MANUFACTURES gradients --
 * an unsharp halo sits at a fixed offset from an edge in IMAGE space, so the same physical
 * edge carries a different synthetic gradient in two views taken from different distances,
 * and the detector locks onto the halo rather than the scene. Denoise ERASES texture --
 * wet rock at ISO 800 is exactly the low-contrast high-frequency detail a denoiser is built
 * to remove, and a matcher has nothing left to hold.
 *
 * It is off by default and it sets nothing at all when off, rather than setting FAST. Those
 * are not the same: FAST is the documented default for the template, but this is a vendor HAL
 * and the archive was shot with whatever it actually chose. Writing a value here would quietly
 * change every future capture and make the comparison with the archive meaningless, which is
 * the failure mode this repo keeps finding in itself. The RESULT is recorded per frame either
 * way, so the first clip shot after this says what the default has been all along.
 */
class CameraSettingRawPixels extends CameraSetting {

    private final boolean mHasEdge;
    private final boolean mHasNoiseReduction;

    public CameraSettingRawPixels(CameraCharacteristics characteristics) {
        mPrefKey = "raw_pixels";
        int[] edge = characteristics.get(
                CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
        int[] nr = characteristics.get(
                CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        mHasEdge = contains(edge, CameraMetadata.EDGE_MODE_OFF);
        mHasNoiseReduction = contains(nr, CameraMetadata.NOISE_REDUCTION_MODE_OFF);
        // Configurable only if the device will actually accept OFF for at least one of them.
        // A switch that cannot change anything is worse than no switch: it would make the
        // setting look answered.
        mConfigurable = mHasEdge || mHasNoiseReduction;
    }

    private static boolean contains(int[] modes, int wanted) {
        if (modes == null) {
            return false;
        }
        for (int m : modes) {
            if (m == wanted) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void updateCaptureRequest(CaptureRequest.Builder builder) {
        if (!mConfigurable || mSharedPreferences == null
                || !mSharedPreferences.getBoolean(mPrefKey, false)) {
            return;   // leave the keys untouched: the HAL's default, as every clip so far
        }
        if (mHasEdge) {
            builder.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
        }
        if (mHasNoiseReduction) {
            builder.set(CaptureRequest.NOISE_REDUCTION_MODE,
                    CameraMetadata.NOISE_REDUCTION_MODE_OFF);
        }
    }
}
