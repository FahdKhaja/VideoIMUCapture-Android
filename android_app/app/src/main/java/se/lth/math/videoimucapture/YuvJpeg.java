package se.lth.math.videoimucapture;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/** A YUV_420_888 frame to a JPEG, in two halves so the encode can leave the camera thread. */
final class YuvJpeg {
    private static final String TAG = "YuvJpeg";

    private YuvJpeg() {
    }

    /**
     * YUV_420_888 -> NV21 -> JPEG.
     *
     * The plane layout is not fixed by the format: chroma may arrive planar
     * (pixelStride 1) or already semi-planar (pixelStride 2), and every plane carries a
     * rowStride that need not equal the width. Assuming either would produce a picture
     * that looks almost right, which is the worst kind of wrong.
     */
    static byte[] yuvToNv21(Image image) {
        try {
            int w = image.getWidth();
            int h = image.getHeight();
            Image.Plane[] p = image.getPlanes();
            byte[] nv21 = new byte[w * h * 3 / 2];

            ByteBuffer y = p[0].getBuffer();
            int yRow = p[0].getRowStride();
            int yPix = p[0].getPixelStride();
            int o = 0;
            if (yRow == w && yPix == 1) {
                y.get(nv21, 0, w * h);
                o = w * h;
            } else {
                byte[] row = new byte[yRow];
                for (int r = 0; r < h; r++) {
                    y.position(r * yRow);
                    int n = Math.min(yRow, y.remaining());
                    y.get(row, 0, n);
                    for (int c = 0; c < w; c++) {
                        nv21[o++] = row[c * yPix];
                    }
                }
            }

            // NV21 chroma is interleaved V then U, at half resolution.
            ByteBuffer u = p[1].getBuffer();
            ByteBuffer v = p[2].getBuffer();
            int uRow = p[1].getRowStride(), uPix = p[1].getPixelStride();
            int vRow = p[2].getRowStride(), vPix = p[2].getPixelStride();
            for (int r = 0; r < h / 2; r++) {
                for (int c = 0; c < w / 2; c++) {
                    int vi = r * vRow + c * vPix;
                    int ui = r * uRow + c * uPix;
                    nv21[o++] = vi < v.limit() ? v.get(vi) : 0;
                    nv21[o++] = ui < u.limit() ? u.get(ui) : 0;
                }
            }
            return nv21;
        } catch (Exception e) {
            Log.e(TAG, "YUV->NV21 failed: " + e);
            return null;
        }
    }

    /** The encode half, off the camera thread. */
    static byte[] nv21ToJpeg(byte[] nv21, int w, int h) {
        try {
            YuvImage yuv =
                    new YuvImage(nv21, ImageFormat.NV21, w, h, null);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            yuv.compressToJpeg(new Rect(0, 0, w, h), 95, bos);
            return bos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "NV21->JPEG failed: " + e);
            return null;
        }
    }
}
