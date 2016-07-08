package com.afollestad.materialcamera.internal;

import android.app.Activity;
import android.app.Fragment;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.media.ToneGenerator;
import android.media.AudioManager;

import com.afollestad.materialcamera.MaterialCamera;
import com.afollestad.materialcamera.R;
import com.afollestad.materialcamera.util.CameraUtil;
import com.afollestad.materialcamera.util.Degrees;
import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import java.io.File;

import static android.app.Activity.RESULT_CANCELED;
import static com.afollestad.materialcamera.internal.BaseCaptureActivity.CAMERA_POSITION_BACK;

/**
 * @author Aidan Follestad (afollestad)
 */
abstract class BaseCameraFragment extends Fragment implements CameraUriInterface, View.OnClickListener {

    protected ImageButton mButtonVideo;
    protected ImageButton mButtonFacing;
    protected TextView mRecordDuration;
    protected TextView mDelayDisplay;
    protected TextView mDelayCounter;

    private int currentDelay = 0;
    private int delay = 0;
    private long delayStart = -1;
    private long delayEnd = -1;
    
    private boolean mIsRecording;
    protected String mOutputUri;
    protected BaseCaptureInterface mInterface;
    protected Handler mPositionHandler;
    protected MediaRecorder mMediaRecorder;

    protected static void LOG(Object context, String message) {
        Log.d(context instanceof Class<?> ? ((Class<?>) context).getSimpleName() :
                context.getClass().getSimpleName(), message);
    }

    private final Runnable mPositionUpdater = new Runnable() {
        @Override
        public void run() {
            if (mInterface == null || mRecordDuration == null) return;
            final long mRecordStart = mInterface.getRecordingStart();
            final long mRecordEnd = mInterface.getRecordingEnd();
            if (mRecordStart == -1 && mRecordEnd == -1) return;
            final long now = System.currentTimeMillis();
            if (mRecordEnd != -1) {
                if (now >= mRecordEnd) {
                    stopRecordingVideo(true);
                } else {
                    final long diff = mRecordEnd - now;
                    mRecordDuration.setText(String.format("-%s", CameraUtil.getDurationString(diff)));
                }
            } else {
                mRecordDuration.setText(CameraUtil.getDurationString(now - mRecordStart));
            }
            if (mPositionHandler != null)
                mPositionHandler.postDelayed(this, 1000);
        }
    };
    
    protected Handler mDelayHandler;
    private final Runnable mDelayUpdater = new Runnable() {
        @Override
        public void run() {
            final long now = System.currentTimeMillis();
            
            if (now >= delayEnd)
            {
                mDelayCounter.setVisibility(View.GONE);
                mButtonVideo.setVisibility(View.VISIBLE);
                mRecordDuration.setVisibility(View.VISIBLE);
                
                ToneGenerator toneG = new ToneGenerator(AudioManager.STREAM_ALARM, 100);
                toneG.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 200); 
                
                stopDelayCounter();
                
                mIsRecording = startRecordingVideo();
            }
            else
            {
                long diff = delayEnd - now;
                diff = (diff / 1000);
                
                mDelayCounter.setText(String.valueOf(diff));
            }
            
            if (mDelayHandler != null)
                mDelayHandler.postDelayed(this, 500);
        }
    };

    @Override
    public final View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.mcam_fragment_videocapture, container, false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        mButtonVideo = (ImageButton) view.findViewById(R.id.video);
        mButtonFacing = (ImageButton) view.findViewById(R.id.facing);
        if (CameraUtil.isArcWelder())
            mButtonFacing.setVisibility(View.GONE);
        mRecordDuration = (TextView) view.findViewById(R.id.recordDuration);
        mButtonFacing.setImageResource(mInterface.getCurrentCameraPosition() == CAMERA_POSITION_BACK ?
                mInterface.iconFrontCamera() : mInterface.iconRearCamera());
        if (mMediaRecorder != null && mIsRecording) {
            mButtonVideo.setImageResource(mInterface.iconStop());
        } else {
            mButtonVideo.setImageResource(mInterface.iconRecord());
            mInterface.setDidRecord(false);
        }

        mButtonVideo.setOnClickListener(this);
        mButtonFacing.setOnClickListener(this);

        final int primaryColor = getArguments().getInt(CameraIntentKey.PRIMARY_COLOR);
        view.findViewById(R.id.controlsFrame).setBackgroundColor(CameraUtil.darkenColor(primaryColor));

        if (savedInstanceState != null)
            mOutputUri = savedInstanceState.getString("output_uri");
        
        mDelayCounter = (TextView) view.findViewById(R.id.delayCounter);
            
        mDelayDisplay = (TextView) view.findViewById(R.id.delayDisplay);
        mDelayDisplay.setText("Delay: " + delay + "s");
        mDelayDisplay.setOnClickListener(this);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mButtonVideo = null;
        mButtonFacing = null;
        mRecordDuration = null;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mInterface != null && mInterface.hasLengthLimit()) {
            if (mInterface.countdownImmediately() || mInterface.getRecordingStart() > -1) {
                if (mInterface.getRecordingStart() == -1)
                    mInterface.setRecordingStart(System.currentTimeMillis());
                startCounter();
            } else {
                mRecordDuration.setText(String.format("-%s", CameraUtil.getDurationString(mInterface.getLengthLimit())));
            }
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public final void onAttach(Activity activity) {
        super.onAttach(activity);
        mInterface = (BaseCaptureInterface) activity;
    }

    @NonNull
    protected final File getOutputMediaFile() {
        return CameraUtil.makeTempFile(getActivity(), getArguments().getString(CameraIntentKey.SAVE_DIR), ".mp4");
    }

    public abstract void openCamera();

    public abstract void closeCamera();

    public void cleanup() {
        closeCamera();
        releaseRecorder();
        stopCounter();
    }

    @Override
    public void onPause() {
        super.onPause();
        cleanup();
    }

    @Override
    public final void onDetach() {
        super.onDetach();
        mInterface = null;
    }

    public final void startCounter() {        
        if (mPositionHandler == null)
            mPositionHandler = new Handler();
        else mPositionHandler.removeCallbacks(mPositionUpdater);
        mPositionHandler.post(mPositionUpdater);
    }
    
    public final void startDelayCounter() {        
        if (mDelayHandler == null)
            mDelayHandler = new Handler();
        else mDelayHandler.removeCallbacks(mDelayUpdater);
        mDelayHandler.post(mDelayUpdater);
    }

    @BaseCaptureActivity.CameraPosition
    public final int getCurrentCameraPosition() {
        if (mInterface == null) return BaseCaptureActivity.CAMERA_POSITION_UNKNOWN;
        return mInterface.getCurrentCameraPosition();
    }

    public final int getCurrentCameraId() {
        if (mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK)
            return (Integer) mInterface.getBackCamera();
        else return (Integer) mInterface.getFrontCamera();
    }

    public final void stopCounter() {
        if (mPositionHandler != null) {
            mPositionHandler.removeCallbacks(mPositionUpdater);
            mPositionHandler = null;
        }
    }
    
    public final void stopDelayCounter() {
        if (mDelayHandler != null) {
            mDelayHandler.removeCallbacks(mDelayUpdater);
            mDelayHandler = null;
        }
    }

    public final void releaseRecorder() {
        if (mMediaRecorder != null) {
            if (mIsRecording) {
                try {
                    mMediaRecorder.stop();
                } catch (Throwable t) {
                    //noinspection ResultOfMethodCallIgnored
                    new File(mOutputUri).delete();
                    t.printStackTrace();
                }
                mIsRecording = false;
            }
            mMediaRecorder.reset();
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }
    
    public void delayStartRecording()
    {
        if (delay == 0)
        {
            mIsRecording = startRecordingVideo();
        }
        else
        {
            mDelayDisplay.setVisibility(View.GONE);
            mButtonVideo.setVisibility(View.GONE);
            mButtonFacing.setVisibility(View.GONE);
            
            delayStart = System.currentTimeMillis();
            delayEnd = delayStart + delay * 1000;
            
            mDelayCounter.setVisibility(View.VISIBLE);
            
            startDelayCounter();
        }
    }

    public boolean startRecordingVideo() {
        if (mInterface != null && mInterface.hasLengthLimit() && !mInterface.countdownImmediately()) {
            // Countdown wasn't started in onResume, start it now
            if (mInterface.getRecordingStart() == -1)
                mInterface.setRecordingStart(System.currentTimeMillis());
            startCounter();
        }

        final int orientation = Degrees.getActivityOrientation(getActivity());
        //noinspection ResourceType
        getActivity().setRequestedOrientation(orientation);
        mInterface.setDidRecord(true);
        return true;
    }

    public void stopRecordingVideo(boolean reachedZero) {
        getActivity().setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    @Override
    public final void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("output_uri", mOutputUri);
    }

    @Override
    public final String getOutputUri() {
        return mOutputUri;
    }

    protected final void throwError(Exception e) {
        Activity act = getActivity();
        if (act != null) {
            act.setResult(RESULT_CANCELED, new Intent().putExtra(MaterialCamera.ERROR_EXTRA, e));
            act.finish();
        }
    }

    @Override
    public void onClick(View view) {
        if (view.getId() == R.id.facing) {
            mInterface.toggleCameraPosition();
            mButtonFacing.setImageResource(mInterface.getCurrentCameraPosition() == BaseCaptureActivity.CAMERA_POSITION_BACK ?
                    mInterface.iconFrontCamera() : mInterface.iconRearCamera());
            closeCamera();
            openCamera();
        } else if (view.getId() == R.id.video) {
            if (mIsRecording) {
                stopRecordingVideo(false);
                mIsRecording = false;
            } else {
                if (getArguments().getBoolean(CameraIntentKey.SHOW_PORTRAIT_WARNING, true) &&
                        Degrees.isPortrait(getActivity())) {
                    new MaterialDialog.Builder(getActivity())
                            .title(R.string.mcam_portrait)
                            .content(R.string.mcam_portrait_warning)
                            .positiveText(R.string.mcam_yes)
                            .negativeText(android.R.string.cancel)
                            .onPositive(new MaterialDialog.SingleButtonCallback() {
                                @Override
                                public void onClick(@NonNull MaterialDialog materialDialog, @NonNull DialogAction dialogAction) {
                                    delayStartRecording();
                                }
                            })
                            .show();
                } else {
                    delayStartRecording();
                }
            }
        } else if (view.getId() == R.id.delayDisplay)
        {
            if (delay == 0)
            {
                delay = 5;
            }
            else if (delay == 5)
            {
                delay = 10;
            }
            else if (delay == 10)
            {
                delay = 20;
            }
            else if (delay == 20)
            {
                delay = 0;
            }
            
            mDelayDisplay.setText("Delay: " + delay + "s");
        }
    }
}