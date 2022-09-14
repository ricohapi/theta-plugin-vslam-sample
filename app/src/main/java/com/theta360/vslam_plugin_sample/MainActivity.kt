package com.theta360.vslam_plugin_sample

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.*
import android.system.OsConstants
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.*
import android.widget.TextView
import com.theta360.pluginlibrary.activity.PluginActivity
import com.theta360.pluginlibrary.activity.ThetaInfo
import com.theta360.pluginlibrary.callback.KeyCallback
import com.theta360.pluginlibrary.receiver.KeyReceiver
import com.theta360.vslam.IVSLAMService
import com.theta360.vslam.IVSLAMServiceListener
import theta360.hardware.Camera
import java.nio.ByteBuffer


class MainActivity : PluginActivity() {
    companion object {
        private val TAG = "vslam_plugin_sample::MainActivity"
        private val RIC_SHOOTING_MODE = "RIC_SHOOTING_MODE"
        private val RIC_PROC_STITCHING = "RIC_PROC_STITCHING"
        private val RIC_PROC_ZENITH_CORRECTION = "RIC_PROC_ZENITH_CORRECTION"
        private val RIC_EXPOSURE_MODE = "RIC_EXPOSURE_MODE"
        private val preview_w = 1024
        private val preview_h = 512
    }
    private var mCamera: Camera? = null
    private var isInitialized: Boolean = false
    private var isCameraOpen:  Boolean = false
    private var isPreview:   Boolean = false
    private var isLowPowerPreview: Boolean = true
    private var mBuffer: ByteArray? = null
    private var mVSLAMService: IVSLAMService? = null
    private var mSharedMem: SharedMemory? = null
    private var mBound: Boolean = false
    private var mVSLAM_processing: Int = -1
    private var mExecVSLAM: Boolean = true

    fun printLog(txt: String?){
        val textView = findViewById<TextView>(R.id.textView)
        textView.textSize = 16f
        textView.setTextColor(R.color.black)
        textView.gravity = Gravity.BOTTOM
        textView.append(txt+"\n")
    }

    private val mVSLAMListener: IVSLAMServiceListener = object : IVSLAMServiceListener.Stub() {
        @Throws(RemoteException::class)
        override fun onUpdatePossition(
            x: Double,
            y: Double,
            z: Double,
            timeStamp: Double,
            status: Int,
            numLands: Long,
            message: String?
        ) {
            val xs = String.format("%.3f", x)
            val ys = String.format("%.3f", y)
            val zs = String.format("%.3f", z)
            val ts = String.format("%.3f", timeStamp)
            Log.i(TAG, "$ts[msec]: Status=$status: ($xs,$ys,$zs), Lands=$numLands, $message")
            val txt = "$ts[msec]: Status=$status: ($xs,$ys,$zs)"
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                printLog(txt)
            }
        }
    }

    private val mServiceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(
            name: ComponentName, service: IBinder
        ) {
            Log.d(TAG, "mServiceConnection::onServiceConnected" + name)
            // VSLAM Service connected
            this@MainActivity.mVSLAMService = IVSLAMService.Stub.asInterface(service)

            try{
                this@MainActivity.mVSLAMService?.let {
                    it.addListener(this@MainActivity.mVSLAMListener)
                    this@MainActivity.mBound = true
                }
            } catch (e: RemoteException){
                Log.e(TAG, "ERROR:" + e.message)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.d(TAG, "mServiceConnection::onServiceDisconnected" + name)
            this@MainActivity.mVSLAMService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.i(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable Text Scrolling
        val textView = findViewById<TextView>(R.id.textView)
        textView.setMovementMethod(ScrollingMovementMethod())

        //KeyCallback
        setKeyCallback(object : KeyCallback {
            override fun onKeyDown(p0: Int, p1: KeyEvent?) {
                if (p0 == KeyReceiver.KEYCODE_CAMERA ||
                    p0 == KeyReceiver.KEYCODE_VOLUME_UP) {  //Bluetooth remote shutter
                    mExecVSLAM = !mExecVSLAM // Pause executing VSLAM
                }
            }
            override fun onKeyUp(p0: Int, p1: KeyEvent?) {
                //do nothing
            }
            override fun onKeyLongPress(p0: Int, p1: KeyEvent) {
                //when camera runs as video recording, just execute stopVideoRecording.
                if (p0 == KeyReceiver.KEYCODE_MEDIA_RECORD) {
                    close()
                }
            }
        })

        val version = ThetaInfo.getThetaFirmwareVersion().replace(".", "").toFloat()
        isLowPowerPreview = if (version >= 1200) true else false    //15fps preview available with fw1.20 or later

        val texture_view = findViewById<TextureView>(R.id.textureView)
        texture_view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(p0: SurfaceTexture, p1: Int, p2: Int) {
                Log.i(TAG, "onSurfaceTextureAvailable")
                openCamera(p0)
            }

            override fun onSurfaceTextureSizeChanged(p0: SurfaceTexture, p1: Int, p2: Int) {
                Log.i(TAG, "onSurfaceTextureSizeChanged")
                //do nothing
            }

            override fun onSurfaceTextureDestroyed(p0: SurfaceTexture): Boolean {
                Log.i(TAG, "onSurfaceTextureDestroyed")
                return false
            }

            override fun onSurfaceTextureUpdated(p0: SurfaceTexture) {
                Log.i(TAG, "onSurfaceTextureUpdated")
            }
        }

        // Allocate Shared Memory
        try {
            mSharedMem = SharedMemory.create("VSLAMframe", getSharedMemSize(preview_w,preview_h))
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "ERROR SharedMemory.create: " + e.message)
        }

        // Launch VSLAM service
        try {
            if (mVSLAMService == null) {
                val packageName = "com.theta360.vslam"
                val className = "com.theta360.vslam.VSLAMService"
                val intent = Intent()
                intent.setClassName(packageName, className)
                bindService(intent, mServiceConnection, BIND_AUTO_CREATE)
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "ERROR on starting VSLAM plugin service: " + e.message)
        }
    }

    override fun onPause() {
        Log.i(TAG,"onPause")
        setAutoClose(false)     //the flag which does not finish plug-in in onPause
        closeCamera()
        unbindService(mServiceConnection)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)  // Enable sleep
        super.onPause()
    }

    override fun onResume() {
        Log.i(TAG,"onResume")
        setAutoClose(true)      //the flag which does finish plug-in by long-press MODE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // Disable sleep
        super.onResume()
    }

    fun openCamera(surface: SurfaceTexture?) {
        Log.i(TAG, "openCamera")
        if (isCameraOpen) {
            return
        }
        //open camera with id setting CAMERA_FACING_DOUBLE directly
        mCamera = Camera.open(this, Camera.CameraInfo.CAMERA_FACING_DOUBLE).apply {
            isCameraOpen = true
            if (surface!=null) {
                setPreviewTexture(surface)
                isInitialized = true
            }
        }
        if(isInitialized) {
            mCamera!!.apply {
                setCameraParameters()
                startPreview()
            }
            isPreview = true
        }
    }

    fun closeCamera() {
        //close camera
        if (isCameraOpen) {
            Log.i(TAG, "closeCamera")
            mVSLAMService?.stop()
            mVSLAM_processing = -1;

            mCamera?.apply {
                stopPreview()
                addCallbackBuffer(null)
                setPreviewCallback(null)
                release()
            }
            mCamera = null
            isCameraOpen = false
        }
    }

    fun getImageSize(w: Int, h: Int):Int{
        var size: Int = w * h
        size = size * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8
        return size
    }

    fun getSharedMemSize(w: Int, h: Int):Int{
        var size: Int = w * h
        size = size + 8
        return size
    }

    fun setCameraParameters() {
        Log.i(TAG, "setCameraParameters")
        mCamera?.let {
            try {
                val p = it.getParameters()
                p.set(RIC_PROC_STITCHING,         "RicDynamicStitchingAuto")
                p.set(RIC_PROC_ZENITH_CORRECTION, "RicZenithCorrectionOnAuto")
                p.set(RIC_EXPOSURE_MODE, "RicAutoExposureP")

                p.set(RIC_SHOOTING_MODE, "RicPreview1024")
                p.setPreviewFrameRate(if(isLowPowerPreview) 0 else 30)
                p.setPreviewSize(preview_w, preview_h)

                it.setBrightnessMode(1) //Auto Control LCD/LED Brightness
                it.setParameters(p)

                // For Preview Callback
                var size: Int = getImageSize(preview_w, preview_h)
                mBuffer = ByteArray(size)
                it.addCallbackBuffer(mBuffer)

                val callback: Camera.PreviewCallbackWithTime = object : Camera.PreviewCallbackWithTime {
                    override fun onPreviewFrame(data: ByteArray?, camera: Camera?, timestamp: Long) {
                        Log.i(TAG, "onPreviewFrame")
                        Log.d(TAG, "PreviewFormat : " + camera?.getParameters()?.getPreviewFormat());
                        Log.d(TAG, "PreviewFrameData : " + data?.size);
                        Log.d(TAG, "timestamp : $timestamp");

                        // Execute VSLAM
                        if(mSharedMem !=null && mVSLAMService !=null && data !=null && mExecVSLAM) {
                            if(mBound && mVSLAM_processing<0){
                                mVSLAM_processing = mVSLAMService?.start("VSLAM-Sample", preview_w, preview_h, mSharedMem)!!
                            }
                            val offset = 0
                            val len = preview_w * preview_h
                            val shmemLen = getSharedMemSize(preview_w,preview_h)
                            val mappedMem = mSharedMem?.map(OsConstants.PROT_WRITE, 0, shmemLen)
                            mappedMem?.let{
                                // ByteArray -> ByteBuffer
                                val tsbytes = ByteBuffer.allocate(8).putLong(timestamp).array() // Timestamp
                                it.put(tsbytes, offset, 8)
                                it.put(data, offset, len); // Image
                                SharedMemory.unmap(it)
                                val result = mVSLAMService?.frame(offset, shmemLen)
                                Log.i(TAG, "onPreviewFrame(), VSLAMService.frame($offset, $len) = $result")
                            }
                        }

                        // Reset buffer
                        if(mCamera!=null) {
                            mCamera!!.addCallbackBuffer(mBuffer)
                        }
                    }
                }
                it.setPreviewCallbackWithBufferAndTime(callback)
            } catch (e: Exception) {
                Log.e(TAG, "Error setCameraParameters: ${e.message}")
            }
        }
    }
}