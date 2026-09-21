/*
 * Copyright 2018 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package se.lth.math.videoimucapture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;

import java.util.ArrayList;

/**
 * Helper class for handling dangerous permissions for Android API level >= 23 which
 * requires user consent at runtime to access the camera.
 *
 * CAMERA is the only hard requirement. Location (GNSS track) and activity recognition
 * (step counter) are requested in the same prompt but recording works without them —
 * the corresponding streams are simply absent from the output.
 */
class PermissionHelper {
    public static final int RC_PERMISSION_REQUEST = 9222;
    /** A second code, for the location-only prompt raised from the settings screen. */
    public static final int RC_LOCATION_REQUEST = 9223;

    public static boolean hasCameraPermission(Activity activity) {
        return ContextCompat.checkSelfPermission(activity,
                Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean mustShowRationale(Activity activity) {
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA);
    }

    public static void requestCameraPermission(Activity activity) {
        ArrayList<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.CAMERA);
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= 29) {
            permissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
        }
        ActivityCompat.requestPermissions(activity,
                permissions.toArray(new String[0]), RC_PERMISSION_REQUEST);
    }

    public static boolean hasLocationPermission(Activity activity) {
        return ContextCompat.checkSelfPermission(activity,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Ask for location on its own, from the settings screen.
     *
     * Separate from the launch prompt because the launch prompt is asked once, before the
     * operator has any idea what the app does with location -- and a "deny" there used to be
     * the end of the matter: the GNSS stream was silently absent from every clip afterwards,
     * with nothing in the UI that could ask again. Turning the GNSS switch on is an explicit
     * request for the thing the permission is for, which is the right moment to ask.
     */
    public static void requestLocationPermission(Activity activity) {
        ActivityCompat.requestPermissions(activity,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, RC_LOCATION_REQUEST);
    }

    /**
     * True when the system will no longer show the prompt -- permanently denied. The only way
     * on from here is the app's own settings page, so the UI has to say that rather than
     * raising a dialog the user will never see.
     */
    public static boolean locationPermanentlyDenied(Activity activity) {
        return !hasLocationPermission(activity)
                && !ActivityCompat.shouldShowRequestPermissionRationale(
                        activity, Manifest.permission.ACCESS_FINE_LOCATION);
    }

    /** The device's location switch, which is not the same thing as the app's permission. */
    public static void launchLocationSettings(Activity activity) {
        activity.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
    }

    /**
     * Launch Application Setting to grant permission.
     */
    public static void launchPermissionSettings(Activity activity) {
        Intent intent = new Intent();
        intent.setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", activity.getPackageName(), null));
        activity.startActivity(intent);
    }

}
