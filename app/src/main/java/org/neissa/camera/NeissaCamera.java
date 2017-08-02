package org.neissa.camera;

import android.app.Activity;
import android.app.Dialog;
import android.app.Fragment;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.*;
import android.view.View.*;

public class NeissaCamera extends Fragment implements View.OnClickListener
{

    private static final SparseIntArray SCREEN_TO_JPEG_ORIENTATIONS = new SparseIntArray();
    static {
        SCREEN_TO_JPEG_ORIENTATIONS.append(Surface.ROTATION_0, 90);
        SCREEN_TO_JPEG_ORIENTATIONS.append(Surface.ROTATION_90, 0);
        SCREEN_TO_JPEG_ORIENTATIONS.append(Surface.ROTATION_180, 270);
        SCREEN_TO_JPEG_ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final String TAG = "NeissaCamera";

    private static final int MAX_RESOLUTION_WIDTH = 4160;
    private static final int MAX_RESOLUTION_HEIGHT = 3120;

    private String mCurrentCameraId;
    private AutoFitTextureView mTextureView;
    private CameraCaptureSession mCaptureSession;
    private CameraDevice mCameraDevice;
	private CameraCharacteristics mCameraCharacteristics;
    private Size mPreviewSize;
    private HandlerThread mBackgroundThread;
    private Handler mBackgroundHandler;
    private ImageReader mImageReader;
    private CaptureRequest.Builder mPreviewRequestBuilder;
	private CaptureRequest mPreviewRequest;
	private int mPictureCount = 0;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1); // prevent the app from exiting before closing the camera.
    private boolean mFlashSupported;
    private int mSensorOrientation;
    
	/* TOOLS */
    private void showToast(final String text)
	{
        final Activity activity = getActivity();
        if (activity != null)
		{
            activity.runOnUiThread(new Runnable() {
					@Override
					public void run()
					{
						Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
					}
				});
        }
    }

	/* VIEW */
    public static NeissaCamera newInstance(String cameraId)
	{
        NeissaCamera c = new NeissaCamera();
		c.mCurrentCameraId = cameraId;
		return c;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
	{
        return inflater.inflate(R.layout.fragment_camera2_basic, container, false);
    }

    @Override
    public void onViewCreated(final View view, Bundle savedInstanceState)
	{
        view.findViewById(R.id.texture).setOnClickListener(this);
        mTextureView = (AutoFitTextureView) view.findViewById(R.id.texture);
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState)
	{
        super.onActivityCreated(savedInstanceState);
    }

    @Override
    public void onResume()
	{
        super.onResume();
        startBackgroundThread();

        // When the screen is turned off and turned back on, the SurfaceTexture is already
        // available, and "onSurfaceTextureAvailable" will not be called. In that case, we can open
        // a camera and start preview from here (otherwise, we wait until the surface is ready in
        // the SurfaceTextureListener).
        if (mTextureView.isAvailable())
		{
            openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        }
		else
		{
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onPause()
	{
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

	/* PREVIEW */
	private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height)
		{
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height)
		{
            configureTransform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture texture)
		{
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture texture)
		{
        }

    };
	private final CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {

        @Override
        public void onOpened(CameraDevice cameraDevice)
		{
            // This method is called when the camera is opened.  We start camera preview here.
            mCameraOpenCloseLock.release();
            mCameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice cameraDevice)
		{
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice cameraDevice, int error)
		{
            mCameraOpenCloseLock.release();
            cameraDevice.close();
            mCameraDevice = null;
            Activity activity = getActivity();
            if (null != activity)
			{
                activity.finish();
            }
        }

    };
	private void openCamera(int width, int height)
	{
        setUpCameraOutputs();
        configureTransform(width, height);
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try
		{
            if(!mCameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS))
			{
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            manager.openCamera(mCurrentCameraId, mStateCallback, mBackgroundHandler);
        }
		catch (Exception e)
		{
            Log.e(TAG,"Erreur",e);
        }
    }
    private void setUpCameraOutputs()
	{
        Activity activity = getActivity();
        CameraManager manager = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
        try
		{
			for(String cameraId : manager.getCameraIdList())
			{
				if(!cameraId.equals(mCurrentCameraId))
					continue;
				//showToast(cameraId);
                mCameraCharacteristics = manager.getCameraCharacteristics(cameraId);

                StreamConfigurationMap map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if(map == null)
				{
                    continue;
                }

                // For still image captures, we use the largest available size.
                Size largest = Collections.max(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), new CompareSizesByArea());
                mImageReader = ImageReader.newInstance(Math.min(MAX_RESOLUTION_WIDTH,largest.getWidth()),Math.min(MAX_RESOLUTION_HEIGHT,largest.getHeight()), ImageFormat.JPEG, 10);
                mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
				
                //noinspection ConstantConditions
                mSensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

                Point displaySize = new Point();
                activity.getWindowManager().getDefaultDisplay().getSize(displaySize);

                // Danger, W.R.! Attempting to use too large a preview size could  exceed the camera
                // bus' bandwidth limitation, resulting in gorgeous previews but the storage of
                // garbage capture data.
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class));

                // We fit the aspect ratio of TextureView to the size of preview we picked.
                int orientation = getResources().getConfiguration().orientation;
                if(orientation == Configuration.ORIENTATION_LANDSCAPE)
				{
                    mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                }
				else
				{
                    mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                }

                // Check if the flash is supported.
                Boolean available = mCameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                mFlashSupported = available == null ? false : available;

                mCurrentCameraId = cameraId;
                return;
            }
        }
		catch (Exception e)
		{
			Log.e(TAG,"Erreur",e);
        }
    }
	private static Size chooseOptimalSize(Size[] choices)
	{
        List<Size> listsizes = new ArrayList<>();
        for (Size option : choices)
			listsizes.add(option);

        // Pick the smallest
        if (listsizes.size() > 0)
		{
            return Collections.min(listsizes, new CompareSizesByArea());
        }
		else
		{
            Log.e(TAG, "Couldn't find any suitable preview size");
            return choices[0];
        }
    }
	static class CompareSizesByArea implements Comparator<Size>
	{

        @Override
        public int compare(Size lhs, Size rhs)
		{
            // We cast here to ensure the multiplications won't overflow
            return Long.signum((long) lhs.getWidth() * (long)lhs.getHeight() - (long) rhs.getWidth() * (long)rhs.getHeight());
        }

    }
	private void configureTransform(int viewWidth, int viewHeight)
	{
        /*Activity activity = getActivity();
        if (null == mTextureView || null == mPreviewSize || null == activity)
		{
            return;
        }
        int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();*/
		/*if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation)
		{
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max((float) viewHeight / (float)mPreviewSize.getHeight(), (float) viewWidth / (float)mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
		else if (Surface.ROTATION_180 == rotation)
		{
            matrix.postRotate(180, centerX, centerY);
        }*/
		//matrix.postTranslate(0, (activity.getWindowManager().getDefaultDisplay().getHeight()-mPreviewSize.getHeight())/2);
        
        //mTextureView.setTransform(matrix);
    }
	
    private void closeCamera()
	{
        try
		{
            mCameraOpenCloseLock.acquire();
            if (null != mCaptureSession)
			{
                mCaptureSession.close();
                mCaptureSession = null;
            }
            if (null != mCameraDevice)
			{
                mCameraDevice.close();
                mCameraDevice = null;
            }
            if (null != mImageReader)
			{
                mImageReader.close();
                mImageReader = null;
            }
        }
		catch (InterruptedException e)
		{
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        }
		finally
		{
            mCameraOpenCloseLock.release();
        }
    }

    private void startBackgroundThread()
	{
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }
    private void stopBackgroundThread()
	{
        mBackgroundThread.quitSafely();
        try
		{
            mBackgroundThread.join();
            mBackgroundThread = null;
            mBackgroundHandler = null;
        }
		catch (Exception e)
		{
            Log.e(TAG,"Erreur",e);
        }
    }

    private void createCameraPreviewSession()
	{
        try
		{
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            // We configure the size of default buffer to be the size of camera preview we want.
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            // This is the output Surface we need to start preview.
            Surface surface = new Surface(texture);

            // We set up a CaptureRequest.Builder with the output Surface.
            mPreviewRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mPreviewRequestBuilder.addTarget(surface);

            // Here, we create a CameraCaptureSession for camera preview.
            mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()),
				new CameraCaptureSession.StateCallback() {

					@Override
					public void onConfigured(CameraCaptureSession cameraCaptureSession)
					{
						// The camera is already closed
						if (null == mCameraDevice)
						{
							return;
						}

						// When the session is ready, we start displaying the preview.
						mCaptureSession = cameraCaptureSession;
						try
						{
							mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
							//mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
							setAutoFlash(mPreviewRequestBuilder);
							mPreviewRequest = mPreviewRequestBuilder.build();
							mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
						}
						catch (Exception e)
						{
							Log.e(TAG,"Erreur",e);
						}
					}

					@Override
					public void onConfigureFailed(CameraCaptureSession cameraCaptureSession)
					{
						showToast("Failed");
					}
				}, null
            );
        }
		catch (Exception e)
		{
            Log.e(TAG,"Erreur",e);
        }
    }

	/* SHOT */
	@Override
    public void onClick(View view)
	{
        takePicture();
    }
    private void takePicture()
	{
		//float minimumLens = mCameraCharacteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
		//float num = 1.0f;//(((float) 100) * minimumLens / 100);
		//mPreviewBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, num);
        lockFocus(0.0f);
		//lockFocus(100.0f);
		//lockFocus(200.0f);
	}
    private void lockFocus(float distance)
	{
		mPictureCount++;
        try
		{
			captureStillPicture();
			/*mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
			mPreviewRequestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, distance);
			mPreviewRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            mCaptureSession.capture(mPreviewRequestBuilder.build(), mCaptureCallback, mBackgroundHandler);*/
        }
		catch (Exception e)
		{
            Log.e(TAG,"Erreur",e);
        }
    }
	private void unlockFocus()
	{
		mPictureCount--;
		if(mPictureCount > 0)
			return;
        try
		{
            //mPreviewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            //setAutoFlash(mPreviewRequestBuilder);
            mCaptureSession.capture(mPreviewRequest, null, mBackgroundHandler);
            mCaptureSession.setRepeatingRequest(mPreviewRequest, null, mBackgroundHandler);
        }
		catch (Exception e)
		{
            Log.e(TAG,"Erreur",e);
        }
    }

	private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {

        private void process(CaptureResult result)
		{
            //if(mCurrentState != STATE_WAITING)
				//return;
			//mCurrentState = STATE_PICTURE_TAKEN;
			captureStillPicture();
        }

        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult result)
		{
            //process(result);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result)
		{
            process(result);
        }

    };

    private void captureStillPicture()
	{
        try
		{
            final Activity activity = getActivity();
            if (null == activity || null == mCameraDevice)
			{
				showToast("nop");
				unlockFocus();
                return;
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            final CaptureRequest.Builder captureBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(mImageReader.getSurface());

            // Use the same AE and AF modes as the preview.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            setAutoFlash(captureBuilder);

            // Orientation
            int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
			int orientation = (SCREEN_TO_JPEG_ORIENTATIONS.get(rotation) + mSensorOrientation + 270) % 360;
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);

            CameraCaptureSession.CaptureCallback CaptureCallback = new CameraCaptureSession.CaptureCallback() {

                @Override
                public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result)
				{
                    //showToast("stop");
                    //Log.d(TAG, mFile.toString());
                    unlockFocus();
                }
            };

            mCaptureSession.stopRepeating();
			//showToast("start");
            mCaptureSession.capture(captureBuilder.build(), CaptureCallback, null);
        }
		catch (Exception e)
		{
            Log.e(TAG,"Erreur",e);
        }
    }

    private void setAutoFlash(CaptureRequest.Builder requestBuilder)
	{
        if (mFlashSupported)
		{
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
        }
    }

	private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new ImageReader.OnImageAvailableListener() {

        @Override
        public void onImageAvailable(ImageReader reader)
		{
			Calendar c = GregorianCalendar.getInstance();
            mBackgroundHandler.post(new ImageSaver(reader.acquireNextImage(), new File(getActivity().getExternalFilesDir(null), "neissa_"+pad(4,c.get(c.YEAR))+pad(2,c.get(c.MONTH))+pad(2,c.get(c.DAY_OF_MONTH))+"_"+pad(2,c.get(c.HOUR))+pad(2,c.get(c.MINUTE))+pad(2,c.get(c.SECOND))+"_"+pad(3,c.get(c.MILLISECOND))+".jpg")));
        }
		public String pad(int nb, int val)
		{
			String v = ""+val;
			while(v.length() < nb)
				v = "0"+nb;
			return v;
		}

    };
    private static class ImageSaver implements Runnable
	{

        private final Image mImage;
        private final File mFile;

        public ImageSaver(Image image, File file)
		{
            mImage = image;
            mFile = file;
        }

        @Override
        public void run()
		{
            ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buffer.remaining()];
            buffer.get(bytes);
            FileOutputStream output = null;
            try
			{
                output = new FileOutputStream(mFile);
                output.write(bytes);
            }
			catch (Exception e)
			{
                Log.e(TAG,"Erreur",e);
            }
			finally
			{
                mImage.close();
                if (null != output)
				{
                    try
					{
                        output.close();
                    }
					catch (Exception e)
					{
                        Log.e(TAG,"Erreur",e);
                    }
                }
            }
        }

    }

}
