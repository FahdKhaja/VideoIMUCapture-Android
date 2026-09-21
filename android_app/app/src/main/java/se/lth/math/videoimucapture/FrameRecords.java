package se.lth.math.videoimucapture;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.ColorSpaceTransform;
import android.hardware.camera2.params.LensIntrinsicsSample;
import android.hardware.camera2.params.OisSample;
import android.hardware.camera2.params.RggbChannelVector;
import android.os.Build;
import android.util.Pair;
import android.util.Size;

import static java.lang.Math.abs;

/**
 * What the camera says, transcribed into the file's records: the once-per-clip CameraInfo and
 * the per-frame VideoFrameMetaData.
 *
 * Transcription and nothing else. No state, no session, no decisions about WHEN a record is
 * written -- that is Camera2Proxy's -- only which result key goes in which field, null-guarded
 * throughout because a HAL may report any subset and a missing key is silence, not a zero.
 */
final class FrameRecords {

    private FrameRecords() {
    }

    static RecordingProtos.CameraInfo cameraInfo(CameraSettingsManager mCameraSettingsManager,
                                                 CameraCharacteristics mCameraCharacteristics,
                                                 FocalLengthHelper mFocalLengthHelper,
                                                 boolean mSwappedDimensions) {

        RecordingProtos.CameraInfo.Builder metaBuilder = RecordingProtos.CameraInfo.newBuilder()
                .setOpticalImageStabilization(mCameraSettingsManager.OISEnabled())
                .setVideoStabilization(mCameraSettingsManager.DVSEnabled())
                .setDistortionCorrection(mCameraSettingsManager.DistortionCorrectionEnabled())
                .setSensorOrientation(mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION));

        Size resolution = mCameraSettingsManager.getVideoSize();
        metaBuilder.setResolution(
                RecordingProtos.CameraInfo.Size.newBuilder()
                        .setHeight(mSwappedDimensions ? resolution.getWidth() : resolution.getHeight())
                        .setWidth(mSwappedDimensions ?  resolution.getHeight() : resolution.getWidth())
        );
        Rect arraySize = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE);
        metaBuilder.setPreCorrectionActiveArraySize(
                RecordingProtos.CameraInfo.Size.newBuilder()
                        .setHeight(arraySize.height())
                        .setWidth(arraySize.width())
        );

        Integer timestamp_source = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE);
        if (timestamp_source != null) {
            metaBuilder.setTimestampSourceValue(timestamp_source);
        }

        Integer focus_cal = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION);
        if (focus_cal != null) {
            metaBuilder.setFocusCalibrationValue(focus_cal);
        }

        float[] lensTranslation = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_TRANSLATION);
        if (lensTranslation != null) {
            for (float lT : lensTranslation) {
                metaBuilder.addLensPoseTranslation(lT);
            }
        }

        float[] lensRotation = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_ROTATION);
        if (lensRotation != null) {
            for (float lR : lensRotation) {
                metaBuilder.addLensPoseRotation(lR);
            }
        }

        float[] intrinsics = mCameraCharacteristics.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION);
        if ((intrinsics != null) && (abs(intrinsics[0]) > 0)) {
            for (float e : mFocalLengthHelper.getTransformedIntrinsic()) {
                metaBuilder.addIntrinsicParams(e);
            }
            for (float e : intrinsics) {
                metaBuilder.addOriginalIntrinsicParams(e);
            }
        }

        if (Build.VERSION.SDK_INT >= 28) {
            float[] distortion = mCameraCharacteristics.get(CameraCharacteristics.LENS_DISTORTION);
            if ((distortion != null) && (abs(distortion[0]) > 0)) {
                for (float e : distortion) {
                    metaBuilder.addDistortionParams(e);
                }
            }
            Integer lensPoseReference = mCameraCharacteristics.get(CameraCharacteristics.LENS_POSE_REFERENCE);
            if (lensPoseReference != null) {
                metaBuilder.setLensPoseReferenceValue(lensPoseReference);
            }
        }
        return metaBuilder.build();
    }

    /**
     * @param focal_length_pix the DERIVED estimate; the HAL's own value has its own field
     *                         (lens_intrinsic_calibration), so both provenances survive.
     */
    static RecordingProtos.VideoFrameMetaData frame(CaptureResult result, Float focal_length_pix,
                                                    FocalLengthHelper mFocalLengthHelper) {
        RecordingProtos.VideoFrameMetaData.Builder frameBuilder = RecordingProtos.VideoFrameMetaData.newBuilder()
                .setTimeNs(result.get(CaptureResult.SENSOR_TIMESTAMP))
                .setFocalLengthMm(result.get(CaptureResult.LENS_FOCAL_LENGTH))
                .setEstFocalLengthPix(focal_length_pix);

        int focus_state = result.get(CaptureResult.CONTROL_AF_STATE);
        frameBuilder.setFocusLocked(focus_state != CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                                 && focus_state != CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN);

        // The following values are allowed to be null
        Long sExp = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        if (sExp != null) {
            frameBuilder.setExposureTimeNs(sExp);
        }

        Long sDur = result.get(CaptureResult.SENSOR_FRAME_DURATION);
        if (sDur != null) {
            frameBuilder.setFrameDurationNs(sDur);
        }

        Long sRoll = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW);
        if (sRoll != null) {
            frameBuilder.setFrameReadoutNs(sRoll);
        }

        Integer sSens = result.get(CaptureResult.SENSOR_SENSITIVITY);
        if (sSens != null) {
            frameBuilder.setIso(sSens);
        }

        Float fDist = result.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (fDist != null) {
            frameBuilder.setFocusDistanceDiopters(fDist);
        }

        // Crop region in active-array coordinates: if it moves frame to frame, EIS is on.
        Rect crop = result.get(CaptureResult.SCALER_CROP_REGION);
        if (crop != null) {
            frameBuilder.setCropRegion(RecordingProtos.VideoFrameMetaData.Rect.newBuilder()
                    .setLeft(crop.left)
                    .setTop(crop.top)
                    .setRight(crop.right)
                    .setBottom(crop.bottom));
        }

        if (Build.VERSION.SDK_INT >= 28) {
            OisSample[] oisSamples = result.get(CaptureResult.STATISTICS_OIS_SAMPLES);
            if (oisSamples != null) {
                for (OisSample sample : oisSamples) {
                    float[] scaledSample = mFocalLengthHelper.transformOISSample(sample);
                    RecordingProtos.VideoFrameMetaData.OISSample.Builder oisBuilder =
                            RecordingProtos.VideoFrameMetaData.OISSample.newBuilder()
                                    .setTimeNs(sample.getTimestamp())
                                    .setXShift(scaledSample[0])
                                    .setYShift(scaledSample[1]);
                    frameBuilder.addOISSamples(oisBuilder);
                }
            }
        }

        writeFrameRadiometry(result, frameBuilder);
        return frameBuilder.build();
    }

    /**
     * Per-frame radiometry (ReconStab #39), everything the CaptureResult already carries so a
     * floating auto-exposure can be undone at bake time and the ISO ceiling of #38 has a number.
     * Every field is null-guarded: a HAL may report any subset, and a missing one is silence,
     * not a zero.
     */
    private static void writeFrameRadiometry(CaptureResult result,
                                      RecordingProtos.VideoFrameMetaData.Builder b) {
        RggbChannelVector gains =
                result.get(CaptureResult.COLOR_CORRECTION_GAINS);
        if (gains != null) {
            b.addColorCorrectionGains(gains.getRed());
            b.addColorCorrectionGains(gains.getGreenEven());
            b.addColorCorrectionGains(gains.getGreenOdd());
            b.addColorCorrectionGains(gains.getBlue());
        }
        ColorSpaceTransform xform =
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM);
        if (xform != null) {
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    b.addColorCorrectionTransform(xform.getElement(col, row).floatValue());
                }
            }
        }
        Integer tonemap = result.get(CaptureResult.TONEMAP_MODE);
        if (tonemap != null) {
            b.setTonemapMode(tonemap);
        }
        Integer boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST);
        if (boost != null) {
            b.setPostRawSensitivityBoost(boost);
        }
        float[] blackLevel = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL);
        if (blackLevel != null) {
            for (float v : blackLevel) {
                b.addDynamicBlackLevel(v);
            }
        }
        Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
        if (aeState != null) {
            b.setAeState(aeState);
        }
        Integer awbState = result.get(CaptureResult.CONTROL_AWB_STATE);
        if (awbState != null) {
            b.setAwbState(awbState);
        }
        Integer aeMode = result.get(CaptureResult.CONTROL_AE_MODE);
        if (aeMode != null) {
            b.setAeMode(aeMode);
        }
        Integer awbMode = result.get(CaptureResult.CONTROL_AWB_MODE);
        if (awbMode != null) {
            b.setAwbMode(awbMode);
        }
        Float aperture = result.get(CaptureResult.LENS_APERTURE);
        if (aperture != null) {
            b.setLensAperture(aperture);
        }
        Integer lensState = result.get(CaptureResult.LENS_STATE);
        if (lensState != null) {
            b.setLensState(lensState);
        }
        // What the hardware DID about stabilization, not what we asked for (ReconStab #41).
        // The request is set from a preference; the result is the HAL's answer, and on a vendor
        // HAL the two are allowed to differ. A gyro-derived blur kernel is only valid while the
        // optical path is fixed, so an unrecorded OIS is a silent invalidation of every kernel.
        Integer ois = result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE);
        if (ois != null) {
            b.setLensOpticalStabilizationMode(ois);
        }
        Integer eis = result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE);
        if (eis != null) {
            b.setVideoStabilizationMode(eis);
        }
        if (Build.VERSION.SDK_INT >= 28) {
            Integer distortion = result.get(CaptureResult.DISTORTION_CORRECTION_MODE);
            if (distortion != null) {
                b.setDistortionCorrectionMode(distortion);
            }
            Integer oisDataMode = result.get(CaptureResult.STATISTICS_OIS_DATA_MODE);
            if (oisDataMode != null) {
                b.setOisDataMode(oisDataMode);
            }
        }
        Integer ev = result.get(CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
        if (ev != null) {
            b.setAeExposureCompensation(ev);
        }
        // What the HAL did to the pixels before the matcher ever sees them (ReconStab #55).
        // These were never set and never read, so the answer for every frame in the archive is
        // "whatever the vendor's video default is" -- and the result is where it is settled,
        // not the request, because a vendor HAL may decline what it is asked for.
        Integer edge = result.get(CaptureResult.EDGE_MODE);
        if (edge != null) {
            b.setEdgeMode(edge);
        }
        Integer nr = result.get(CaptureResult.NOISE_REDUCTION_MODE);
        if (nr != null) {
            b.setNoiseReductionMode(nr);
        }
        // Intrinsics sampled WITHIN the capture (#50). API 35; this device runs 36 and lists the
        // key on all seven cameras. Whether it fills it is a different question -- the key beside
        // it, oisSamples, is listed on all seven and returns null on every frame -- so this is
        // read null-guarded like everything else and graded PRESENT / EMPTY / ABSENT afterwards.
        if (Build.VERSION.SDK_INT >= 35) {
            LensIntrinsicsSample[] samples =
                    result.get(CaptureResult.STATISTICS_LENS_INTRINSICS_SAMPLES);
            if (samples != null) {
                for (LensIntrinsicsSample s : samples) {
                    RecordingProtos.VideoFrameMetaData.LensIntrinsicsSample.Builder sb =
                            RecordingProtos.VideoFrameMetaData.LensIntrinsicsSample.newBuilder()
                                    .setTimeNs(s.getTimestampNanos());
                    float[] k = s.getLensIntrinsics();
                    if (k != null) {
                        for (float v : k) {
                            sb.addIntrinsics(v);
                        }
                    }
                    b.addLensIntrinsicsSamples(sb);
                }
            }
        }
        Pair<Double, Double>[] noise = result.get(CaptureResult.SENSOR_NOISE_PROFILE);
        if (noise != null) {
            for (Pair<Double, Double> p : noise) {
                b.addNoiseProfile(p.first);
                b.addNoiseProfile(p.second);
            }
        }
        // Per-frame lens intrinsics, IF the HAL reports them dynamically (#31). Most devices only
        // expose the static characteristic; where this is non-null it captures focus breathing.
        float[] intrinsics = result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION);
        if (intrinsics != null) {
            for (float v : intrinsics) {
                b.addLensIntrinsicCalibration(v);
            }
        }
    }
}
