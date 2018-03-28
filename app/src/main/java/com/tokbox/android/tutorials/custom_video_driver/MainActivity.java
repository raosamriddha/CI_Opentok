package com.tokbox.android.tutorials.custom_video_driver;

import android.Manifest;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View.OnClickListener;
import android.view.View;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.opentok.android.BaseVideoRenderer;
import com.opentok.android.OpentokError;
import com.opentok.android.Publisher;
import com.opentok.android.PublisherKit;
import com.opentok.android.Session;
import com.opentok.android.Stream;
import com.opentok.android.Subscriber;
import com.opentok.android.SubscriberKit;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.IFrameCallback;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.AppSettingsDialog;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends AppCompatActivity
        implements EasyPermissions.PermissionCallbacks, Session.SessionListener, PublisherKit.PublisherListener,
        SubscriberKit.SubscriberListener, Subscriber.VideoListener, CameraDialog.CameraDialogParent {

    private static final String TAG = MainActivity.class.getSimpleName();

    private final int MAX_NUM_SUBSCRIBERS = 3;
    private static final int RC_SETTINGS_SCREEN_PERM = 123;
    private static final int RC_VIDEO_APP_PERM = 124;
    private CustomWebcamCapturer mCapturer;


    private Camera camera;
    private ImageButton flash_on;
    private ImageButton flash_off;
    private ImageButton swap_camera;
    private Session mSession;
    private Publisher mPublisher;
    private Subscriber mSubscriber;
    private String is_active = "false";
    private String is_front = "false";
    private String swap_locked = "false";
    private String flash_locked = "false";
    private ImageButton mUSBButton;

    // Publisher view layout
    private RelativeLayout mPublisherViewContainer;


    // For multiple subscribers
    private ArrayList<Subscriber> mSubscribers = new ArrayList<Subscriber>();
    private HashMap<Stream, Subscriber> mSubscriberStreams = new HashMap<Stream, Subscriber>();

    private CustomVideoCapturer customVideo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate( savedInstanceState );
        setContentView( R.layout.activity_main );


        flash_on = (ImageButton) findViewById( R.id.flash_on );

        flash_off = (ImageButton) findViewById( R.id.flash_off );

        swap_camera = (ImageButton) findViewById( R.id.camera_swap );


        mPublisherViewContainer = (RelativeLayout) findViewById( R.id.publisherview );

        mUSBMonitor = new USBMonitor( MainActivity.this, mOnDeviceConnectListener );

        mUSBButton = (ImageButton) findViewById( R.id.usb_on );
        mUSBButton.setOnClickListener( mOnClickListener );

        requestPermissions();
    }


    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    protected void onRestart() {
        super.onRestart();
    }

    @Override
    protected void onPause() {
        Log.d( TAG, "onPause" );

        super.onPause();

        if (mSession == null) {
            return;
        }
        mSession.onPause();

        if (isFinishing()) {
            disconnectSession();
        }
    }

    @Override
    protected void onResume() {
        Log.d( TAG, "onResume" );

        super.onResume();

        if (mSession == null) {
            return;
        }
        mSession.onResume();
    }


    @Override
    public void onDestroy() {
        if (camera != null) {
            camera.release();
            camera = null;
        }

        if (mUVCCamera != null) {
            mUVCCamera.destroy();
            mUVCCamera = null;
        }

        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }


        disconnectSession();
        super.onDestroy();
    }


    private final OnClickListener mOnClickListener = new View.OnClickListener() {

        @Override
        public void onClick(final View view) {
            if (is_active.equalsIgnoreCase( "false" )) {
                if (mPublisher != null) {
                    mSession.unpublish( mPublisher );
                }
                getUSBPublisher();

                mUSBMonitor.register();

                if (mUVCCamera == null) {
                    // XXX calling CameraDialog.showDialog is necessary at only first time(only when app has no permission).

                    CameraDialog.showDialog( MainActivity.this );

                } else {

                    mUVCCamera.destroy();
                    mUVCCamera = null;
                    isActive = isPreview = false;

                }
            }

        }
    };


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult( requestCode, permissions, grantResults );

        EasyPermissions.onRequestPermissionsResult( requestCode, permissions, grantResults, this );
    }

    @Override
    public void onPermissionsGranted(int requestCode, List<String> perms) {
        Log.d( TAG, "onPermissionsGranted:" + requestCode + ":" + perms.size() );
    }

    @Override
    public void onPermissionsDenied(int requestCode, List<String> perms) {
        Log.d( TAG, "onPermissionsDenied:" + requestCode + ":" + perms.size() );

        if (EasyPermissions.somePermissionPermanentlyDenied( this, perms )) {
            new AppSettingsDialog.Builder( this )
                    .setTitle( getString( R.string.title_settings_dialog ) )
                    .setRationale( getString( R.string.rationale_ask_again ) )
                    .setPositiveButton( getString( R.string.setting ) )
                    .setNegativeButton( getString( R.string.cancel ) )
                    .setRequestCode( RC_SETTINGS_SCREEN_PERM )
                    .build()
                    .show();
        }
    }

    @AfterPermissionGranted(RC_VIDEO_APP_PERM)
    private void requestPermissions() {
        String[] perms = {Manifest.permission.INTERNET, Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        if (EasyPermissions.hasPermissions( this, perms )) {
            mSession = new Session.Builder( MainActivity.this, OpenTokConfig.API_KEY, OpenTokConfig.SESSION_ID ).build();
            mSession.setSessionListener( this );
            mSession.connect( OpenTokConfig.TOKEN );
        } else {
            EasyPermissions.requestPermissions( this, getString( R.string.rationale_video_app ), RC_VIDEO_APP_PERM, perms );
        }
    }

    private void getCameraPublisher() {

        customVideo = new CustomVideoCapturer( MainActivity.this, Publisher.CameraCaptureResolution.MEDIUM, Publisher.CameraCaptureFrameRate.FPS_30 );
        mPublisher = new Publisher.Builder( MainActivity.this )
                .name( "publisher" )
                .capturer( customVideo )
                .build();
        mPublisher.setPublisherListener( this );

        mPublisher.setStyle( BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL );
        mPublisherViewContainer.addView( mPublisher.getView() );

        mSession.publish( mPublisher );


        // Flash on button click listen after session established
        flash_on.setOnClickListener( new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (flash_locked.equalsIgnoreCase( "false" )) {
                    customVideo.turnOnFlash();
                    if (is_front.equalsIgnoreCase( "true" )) {
                        flash_off.setImageResource( R.drawable.flash_off_disable );
                        flash_on.setImageResource( R.drawable.flash_disable );
                    } else {
                        flash_off.setImageResource( R.drawable.flash_off );
                        flash_on.setImageResource( R.drawable.flash_disable );
                    }
                }
            }
        } );

        // Flash off button click listen after session established
        flash_off.setOnClickListener( new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                if (flash_locked.equalsIgnoreCase( "false" )) {
                    customVideo.turnOffFlash();
                    if (is_front.equalsIgnoreCase( "true" )) {
                        flash_off.setImageResource( R.drawable.flash_off_disable );
                        flash_on.setImageResource( R.drawable.flash_disable );
                    } else {
                        flash_off.setImageResource( R.drawable.flash_off_disable );
                        flash_on.setImageResource( R.drawable.flash_on );
                    }
                }
            }
        } );

        // Swap camera button click listen after session established
        swap_camera.setOnClickListener( new View.OnClickListener() {

            @Override
            public void onClick(View v) {

                if (swap_locked.equalsIgnoreCase( "false" )) {
                    customVideo.swapCamera();
                    int status = customVideo.getSwapCameraStatus();
                    if (status == 0) {
                        is_front = "true";
                        flash_on.setImageResource( R.drawable.flash_disable );
                        flash_off.setImageResource( R.drawable.flash_off_disable );
                    } else {
                        is_front = "false";
                        flash_on.setImageResource( R.drawable.flash_on );
                        flash_off.setImageResource( R.drawable.flash_off_disable );
                    }
                }

            }
        } );
    }

    private void getUSBPublisher() {
        mPublisher = new Publisher.Builder( MainActivity.this )
                .name( "publisher" )
                .capturer( new CustomWebcamCapturer( this ) )
                .build();
        mPublisher.setPublisherListener( this );

        mPublisher.setStyle( BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL );


    }

    @Override
    public void onConnected(Session session) {

        Log.d( TAG, "onConnected: Connected to session " + session.getSessionId() );
        // Get camera view on start
        getCameraPublisher();

    }

    @Override
    public void onDisconnected(Session session) {
        Log.d( TAG, "onDisconnected: disconnected from session " + session.getSessionId() );
        mSession = null;
    }

    @Override
    public void onError(Session session, OpentokError opentokError) {
        Log.d( TAG, "onError: Error (" + opentokError.getMessage() + ") in session " + session.getSessionId() );

        Toast.makeText( this, opentokError.getMessage(), Toast.LENGTH_LONG ).show();
        finish();
    }

    @Override
    public void onStreamReceived(Session session, Stream stream) {
        Log.d( TAG, "onStreamReceived: New stream " + stream.getStreamId() + " in session " + session.getSessionId() );

        if (mSubscribers.size() + 1 > MAX_NUM_SUBSCRIBERS) {
            Toast.makeText( this, "New subscriber ignored. MAX_NUM_SUBSCRIBERS limit reached.", Toast.LENGTH_LONG ).show();
            return;
        }

        final Subscriber subscriber = new Subscriber.Builder( MainActivity.this, stream ).build();
        mSession.subscribe( subscriber );
        mSubscribers.add( subscriber );
        mSubscriberStreams.put( stream, subscriber );

        int position = mSubscribers.size() - 1;
        int id = getResources().getIdentifier( "subscriberview" + (new Integer( position )).toString(), "id", MainActivity.this.getPackageName() );
        RelativeLayout subscriberViewContainer = (RelativeLayout) findViewById( id );

        subscriber.setStyle( BaseVideoRenderer.STYLE_VIDEO_SCALE, BaseVideoRenderer.STYLE_VIDEO_FILL );
        subscriberViewContainer.addView( subscriber.getView() );

    }

    // Setting subscriber views according to their Ids assigned
    @Override
    public void onStreamDropped(Session session, Stream stream) {
        Log.d( TAG, "onStreamDropped: Stream " + stream.getStreamId() + " dropped from session " + session.getSessionId() );

        Subscriber subscriber = mSubscriberStreams.get( stream );
        if (subscriber == null) {
            return;
        }

        int position = mSubscribers.indexOf( subscriber );
        int id = getResources().getIdentifier( "subscriberview" + (new Integer( position )).toString(), "id", MainActivity.this.getPackageName() );

        mSubscribers.remove( subscriber );
        mSubscriberStreams.remove( stream );

        RelativeLayout subscriberViewContainer = (RelativeLayout) findViewById( id );
        subscriberViewContainer.removeView( subscriber.getView() );


    }

    @Override
    public void onStreamCreated(PublisherKit publisherKit, Stream stream) {
        Log.d( TAG, "onStreamCreated: Own stream " + stream.getStreamId() + " created" );

        if (!OpenTokConfig.SUBSCRIBE_TO_SELF) {
            return;
        }

        subscribeToStream( stream );
    }

    @Override
    public void onStreamDestroyed(PublisherKit publisherKit, Stream stream) {
        Log.d( TAG, "onStreamDestroyed: Own stream " + stream.getStreamId() + " destroyed" );
    }

    @Override
    public void onError(PublisherKit publisherKit, OpentokError opentokError) {
        Log.d( TAG, "onError: Error (" + opentokError.getMessage() + ") in publisher" );

        Toast.makeText( this, opentokError.getMessage(), Toast.LENGTH_LONG ).show();
        finish();
    }


    @Override
    public void onConnected(SubscriberKit subscriberKit) {

    }

    @Override
    public void onDisconnected(SubscriberKit subscriberKit) {

    }

    @Override
    public void onError(SubscriberKit subscriberKit, OpentokError opentokError) {

    }

    @Override
    public void onVideoDataReceived(SubscriberKit subscriberKit) {

    }

    @Override
    public void onVideoDisabled(SubscriberKit subscriberKit, String s) {

    }

    @Override
    public void onVideoEnabled(SubscriberKit subscriberKit, String s) {

    }

    @Override
    public void onVideoDisableWarning(SubscriberKit subscriberKit) {

    }

    @Override
    public void onVideoDisableWarningLifted(SubscriberKit subscriberKit) {

    }

    private void subscribeToStream(Stream stream) {
        mSubscriber = new Subscriber.Builder( MainActivity.this, stream ).build();
        mSubscriber.setVideoListener( this );
        mSession.subscribe( mSubscriber );
    }

    private void disconnectSession() {
        if (mSession == null) {
            return;
        }

        if (mSubscribers.size() > 0) {
            for (Subscriber subscriber : mSubscribers) {
                if (subscriber != null) {
                    mSession.unsubscribe( subscriber );
                    subscriber.destroy();
                }
            }
        }

        if (mPublisher != null) {
            mPublisherViewContainer.removeView( mPublisher.getView() );
            mSession.unpublish( mPublisher );
            mPublisher.destroy();
            mPublisher = null;
        }
        mSession.disconnect();
    }


    // for thread pool
    private static final int CORE_POOL_SIZE = 1;        // initial/minimum threads
    private static final int MAX_POOL_SIZE = 4;            // maximum threads
    private static final int KEEP_ALIVE_TIME = 10;        // time periods while keep the idle thread
    protected static final ThreadPoolExecutor EXECUTER
            = new ThreadPoolExecutor( CORE_POOL_SIZE, MAX_POOL_SIZE, KEEP_ALIVE_TIME,
            TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>() );


    // for accessing USB and USB camera
    private USBMonitor mUSBMonitor;
    private UVCCamera mUVCCamera;
    private SurfaceView mUVCCameraView;
    // for open&start / stop&close camera preview
    private SurfaceTexture mPreviewSurface;
    private boolean isActive, isPreview;

    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {

            Toast.makeText( getApplicationContext(), "USB_DEVICE_ATTACHED", Toast.LENGTH_SHORT ).show();
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {

            mPreviewSurface = new SurfaceTexture( 42 );
            if (mUVCCamera != null)
                mUVCCamera.destroy();
            isActive = isPreview = false;

            EXECUTER.execute( new Runnable() {
                @Override
                public void run() {

                    mUVCCamera = new UVCCamera();
                    mUVCCamera.open( ctrlBlock );

                    try {
                        mUVCCamera.setPreviewSize( UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG );
                    } catch (final IllegalArgumentException e) {
                        try {
                            // fallback to YUV mode
                            mUVCCamera.setPreviewSize( UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.DEFAULT_PREVIEW_MODE );
                        } catch (final IllegalArgumentException e1) {
                            mUVCCamera.destroy();
                            mUVCCamera = null;
                        }
                    }
                    if ((mUVCCamera != null) && (mPreviewSurface != null)) {
                        isActive = true;
                        mUVCCamera.setPreviewTexture( mPreviewSurface );
                        mUVCCamera.setFrameCallback( mIFrameCallback, UVCCamera.PIXEL_FORMAT_NV21 );
                        mUVCCamera.startPreview();
                        isPreview = true;

                    }

                }
            } );

            runOnUiThread( new Runnable() {
                @Override
                public void run() {

                    if (mPublisherViewContainer != null) {
                        mPublisherViewContainer.removeAllViews();
                    }
                    mPublisherViewContainer.addView( mPublisher.getView() );
                    mUSBButton.setImageResource( R.drawable.usb_disable );
                    swap_camera.setImageResource( R.drawable.switch_disable );
                    flash_on.setImageResource( R.drawable.flash_disable );
                    flash_off.setImageResource( R.drawable.flash_off_disable );
                    is_active = swap_locked = flash_locked = "true";
                    mSession.publish( mPublisher );
                }
            } );
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {


            Log.d( TAG, "DISCONNECTED-------------------->>>>>>>>>>>>" );

            // check whether the comming device equal to camera device that currently using

            if (mUVCCamera != null) {
                mUVCCamera.destroy();
                mUVCCamera = null;
                isActive = false;
            }
            runOnUiThread( new Runnable() {
                @Override
                public void run() {

                    if (mPublisher != null) {
                        mPublisherViewContainer.removeAllViews();
                        mPublisher.destroy();
                        mPublisher = null;
                    }

                    mUSBMonitor.unregister();


                    // Enabling/Disabling buttons accordingly
                    mUSBButton.setImageResource( R.drawable.usb_on );
                    swap_camera.setImageResource( R.drawable.camera_swap );
                    is_active = swap_locked = flash_locked = "false";
                }
            } );

        }

        @Override
        public void onCancel(UsbDevice device) {

        }

        @Override
        public void onDettach(final UsbDevice device) {
            runOnUiThread( new Runnable() {
                @Override
                public void run() {

                    // Getting back to camera view
                    getCameraPublisher();

                    // Enabling/Disabling buttons accordingly
                    mUSBButton.setImageResource( R.drawable.usb_on );
                    swap_camera.setImageResource( R.drawable.camera_swap );
                    flash_on.setImageResource( R.drawable.flash_on );
                    flash_off.setImageResource( R.drawable.flash_off );

                    is_active = swap_locked = flash_locked = "false";
                }
            } );
        }

        public void onCancel() {
        }
    };

    /**
     * to access from CameraDialog
     *
     * @return
     */
    public USBMonitor getUSBMonitor() {

        mCapturer = (CustomWebcamCapturer) mPublisher.getCapturer();
        mCapturer.addFrame( null );
        new Thread( new MyThread( mCapturer ) ).start();
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {

    }

    class MyThread implements Runnable {
        private CustomWebcamCapturer mCapturer;
        private Long lastCameraTime = 0L;

        public MyThread(CustomWebcamCapturer cap) {
            mCapturer = cap;
        }

        @Override
        public void run() {
            while (true) {

                byte[] capArray = null;
                imageArrayLock.lock();

                if (lastCameraTime != imageTime) {
                    lastCameraTime = System.currentTimeMillis();
                    capArray = imageArray;
                }
                imageArrayLock.unlock();
                mCapturer.addFrame( capArray );
                try {
                    Thread.sleep( 10 );
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }


    private byte[] imageArray = null;
    private Long imageTime = 0L;
    private ReentrantLock imageArrayLock = new ReentrantLock();
    private final IFrameCallback mIFrameCallback = new IFrameCallback() {
        @Override
        public void onFrame(final ByteBuffer frame) {
            imageArrayLock.lock();

            imageArray = new byte[frame.remaining()];
            frame.get( imageArray );

            if (imageArray == null) {
                Log.d( TAG, "onFrame Lock NULL" );
            } else {

            }

            imageTime = System.currentTimeMillis();
            imageArrayLock.unlock();
        }
    };


}

