package com.example.camera2api

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.CamcorderProfile
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.view.View.OnLongClickListener
import android.widget.Chronometer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camera2api.databinding.ActivityMainBinding
import java.io.*
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*


open class MainActivity : AppCompatActivity() {

    private val TAG = "MainActivity"
    private var textureView: TextureView? = null
    private val ORIENTATIONS = SparseIntArray()

    private var mCameraId: String? = null
    protected var mCameraDevice: CameraDevice? = null
    protected var mCameraCaptureSessions: CameraCaptureSession? = null
    private var mCaptureRequestBuilder: CaptureRequest.Builder? = null
    private var mImageDimension: Size? = null
    private var mImageReader: ImageReader? = null
    private val REQUEST_CAMERA_PERMISSION = 200
    private var mBackgroundHandler: Handler? = null
    private var mBackgroundThreadHandler: HandlerThread? = null
    private lateinit var mainBinding: ActivityMainBinding
    private var mIsRecordingVideo:Boolean = false
    private lateinit var mMediaRecorder: MediaRecorder
    private lateinit var mRecordCaptureSession: CameraCaptureSession
    private lateinit var mVideoSize: Size
    private lateinit var mPreviewSize: Size
    private lateinit var mImageSize: Size
    private lateinit var mImageFileName: String
    private lateinit var mImageFolder: File
    private lateinit var mVideoFolder: File
    private lateinit var mVideoFileName: String
    private var mChronometer: Chronometer? = null
    private var mTotalRotation: Int = 0
    private var mIsTimeLapse: Boolean   = false

    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened")
            mCameraDevice = camera
            mMediaRecorder = MediaRecorder()
            if (mIsRecordingVideo) {
                try {
                    createVideoFileName()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
                mChronometer!!.visibility = View.VISIBLE
                mMediaRecorder.start()
                mChronometer!!.start()
                startRecordingVideo()

            } else {
                createCameraPreview()
            }
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera?.close()
            mCameraDevice = null
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera?.close()
            mCameraDevice = null
        }
    }

    private var textureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
            //open your camera here
            setupCamera(width, height)
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Transform you image captured size according to the surface width and height
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
            return false
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    }


    private val mOnImageAvailableListener =
        ImageReader.OnImageAvailableListener { reader ->
            mBackgroundHandler!!.post(ImageSaver(reader.acquireLatestImage(), mImageFileName))

        }

    private var isVideoPaused: Boolean = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)
        textureView = mainBinding.textureView
        assert(textureView != null)
        textureView!!.surfaceTextureListener = textureListener

        mChronometer = mainBinding.chronometer

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
        createVideoFolder()
        createImageFolder()
        mainBinding.chronometer.visibility = View.GONE
        mainBinding.btnTakePicture.setOnClickListener {
            takePicture()
        }
        mainBinding.btnStartStop.setOnClickListener {
            if (mIsRecordingVideo ) {
                mainBinding.btnStartStop.setImageResource(R.drawable.ic_video_icon)

                mChronometer!!.base = SystemClock.elapsedRealtime();
                mChronometer!!.stop();
                mChronometer!!.visibility = View.GONE
                stopRecordingVideo()

                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
                sendBroadcast(mediaStoreUpdateIntent)
                Toast.makeText(this, "Video saved to file explorer", Toast.LENGTH_SHORT).show()
            } else {
                mIsRecordingVideo = true
                mainBinding.btnStartStop.setImageResource(R.drawable.ic_stop_icon)
                checkPermissions()
            }
        }
        var timeWhenPaused: Long = 0
        mainBinding.btnPauseResume.setOnClickListener {
            if (isVideoPaused) {
                isVideoPaused = false
                mainBinding.btnPauseResume.setImageResource(R.drawable.ic_pause_icon)
                mChronometer!!.base = SystemClock.elapsedRealtime() + timeWhenPaused
                mChronometer!!.start()
                mMediaRecorder.resume()
            } else {
                isVideoPaused = true
                mainBinding.btnPauseResume.setImageResource(R.drawable.ic_resume_icon)
                timeWhenPaused = mChronometer!!.base - SystemClock.elapsedRealtime()
                mChronometer!!.stop()
                mMediaRecorder.pause()
            }
        }
    }

    private  class ImageSaver(private val mImage: Image, private val imageFileName: String) : Runnable {
        override fun run() {
            val byteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer[bytes]
            var fileOutputStream: FileOutputStream? = null
            try {
                fileOutputStream = FileOutputStream(imageFileName)
                fileOutputStream!!.write(bytes)
            } catch (e: IOException) {
                e.printStackTrace()
            } finally {
                mImage.close()
                val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                mediaStoreUpdateIntent.data = Uri.fromFile(File(imageFileName))
                /**
                 *  TODO : sendBroadcast(mediaStoreUpdateIntent) // not working for image
                 */
                if (fileOutputStream != null) {
                    try {
                        fileOutputStream.close()
                    } catch (e: IOException) {
                        e.printStackTrace()
                    }
                }
            }
        }



    }


    /**
     * setup camera to take picture
     */
    private fun setupCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            for(cameraID in cameraManager.cameraIdList) {
                val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraID)
                if(cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT){
                    continue
                }

                val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation = windowManager.defaultDisplay.rotation
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                val swapRotation: Boolean = (mTotalRotation == 90 || mTotalRotation == 270)
                var rotatedWidth: Int = width
                var rotatedHeight: Int = height
                if(swapRotation) {
                     rotatedWidth = height
                     rotatedHeight = width
                }
                mPreviewSize = chooseOptimalSize(map!!.getOutputSizes(SurfaceTexture::class.java), rotatedWidth, rotatedHeight)
                mVideoSize = chooseOptimalSize(map.getOutputSizes(MediaRecorder::class.java), rotatedWidth, rotatedHeight)
                mImageSize = chooseOptimalSize(map.getOutputSizes(ImageFormat.JPEG), rotatedWidth, rotatedHeight)
                mImageReader = ImageReader.newInstance(mImageSize.width, mImageSize.height, ImageFormat.JPEG, 1)
                mImageReader!!.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler)
            }
        } catch (e: Exception) {
           Log.e(TAG, e.message.toString())
        }
     }
    private fun stopRecordingVideo() {
        if (!mIsRecordingVideo) {
            return
        }
        mIsRecordingVideo = false
        mMediaRecorder.stop()
        mMediaRecorder.reset()
        createCameraPreview()
        closeCamera()


    }

    /**
     * check permissions to access camera hardware
     */
    private  fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED
                    ) {

                    createVideoFileName()
                    startRecordingVideo()
                    mMediaRecorder.start()
                    mChronometer!!.base = SystemClock.elapsedRealtime()
                    mChronometer!!.visibility = View.VISIBLE
                    mChronometer!!.start();

            } else {
                if (shouldShowRequestPermissionRationale(android.Manifest.permission.WRITE_EXTERNAL_STORAGE )) {
                    Toast.makeText(this, "app needs permission to be able to save videos", Toast.LENGTH_SHORT)
                        .show()
                }
                requestPermissions(
                    arrayOf(android.Manifest.permission.CAMERA, android.Manifest.permission.WRITE_EXTERNAL_STORAGE, android.Manifest.permission.RECORD_AUDIO),
                    REQUEST_CAMERA_PERMISSION
                )
            }

    }

    private fun chooseOptimalSize(outputSizes: Array<Size>?, width: Int, height: Int): Size {
        var bigEnough: MutableList<Size> = ArrayList()
        for (option in outputSizes!!) {
            if (option.height == option.width * height / width && option.width >= width && option.height >= height) {
                bigEnough.add(option)
            }
        }
        return if (bigEnough.size > 0) {
            Collections.min(bigEnough, CompareSizeByArea())
        } else {
            outputSizes[0]
        }
    }
     class CompareSizeByArea : Comparator<Size?> {

         override fun compare(lhs: Size?, rhs: Size?): Int {
             return java.lang.Long.signum((lhs!!.width!! * lhs.height).toLong() - (rhs!!.width * rhs.height).toLong())
         }
     }

    private val captureSession = object : CameraCaptureSession.StateCallback() {
        override fun onConfigureFailed(session: CameraCaptureSession) { }
        override fun onConfigured(session: CameraCaptureSession) { }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult,
        ) { }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) { }
    }

    private fun startRecordingVideo() {
        try {

            setUpMediaRecorder()

            val surfaceTexture: SurfaceTexture? = mainBinding.textureView.surfaceTexture
            surfaceTexture!!.setDefaultBufferSize(mPreviewSize.width, mPreviewSize.height)
            val previewSurface = Surface(surfaceTexture)
            val recordSurface = mMediaRecorder.surface
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            mCaptureRequestBuilder!!.addTarget(previewSurface)
            mCaptureRequestBuilder!!.addTarget(recordSurface)
            mCameraDevice!!.createCaptureSession(
                listOf(
                previewSurface,
                recordSurface,
                mImageReader!!.surface
            ),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        mRecordCaptureSession = session
                        try {
                            mRecordCaptureSession.setRepeatingRequest(
                                mCaptureRequestBuilder!!.build(), null, null
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.d(TAG, "onConfigureFailed: startRecord")
                    }
                }, null
            )
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun setUpMediaRecorder() {
        try {

            mMediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(mVideoFileName)
                setVideoEncodingBitRate(3000000)
                setVideoFrameRate(30)
                setVideoSize(640, 480)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOrientationHint(mTotalRotation);
                prepare()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to set up MediaRecorder: ${e.message}")
        }
    }

    private fun sensorToDeviceRotation(
        cameraCharacteristics: CameraCharacteristics,
        deviceOrientation: Int,
    ): Int {
        var deviceOrientation = deviceOrientation
        val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        deviceOrientation = ORIENTATIONS[deviceOrientation]
        return (sensorOrientation + deviceOrientation + 360) % 360
    }

    private fun createVideoFolder() {
        val movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        mVideoFolder = File(movieFile, "camera2VideoImage")
        if (!mVideoFolder.exists()) {
            mVideoFolder.mkdirs()
        }
    }

    private  fun createImageFolder() {
        val imageFile =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        mImageFolder = File(imageFile, "camera2VideoImage")
        if (!mImageFolder.exists()) {
            mImageFolder.mkdirs()
        }
    }

    private  fun createImageFileName(): File? {
        val timestamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_" + timestamp + "_"
        val imageFile = File.createTempFile(prepend, ".jpg", mImageFolder)
        mImageFileName = imageFile.absolutePath
        return imageFile
    }

    private  fun createVideoFileName(): File? {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "VIDEO_" + timestamp + "_"
        val videoFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
        mVideoFileName = videoFile.absolutePath
        return videoFile
    }

    private fun startBackgroundThread() {
        mBackgroundThreadHandler = HandlerThread("Camera Background")
        mBackgroundThreadHandler!!.start()
        mBackgroundHandler = Handler(mBackgroundThreadHandler!!.looper)
    }

    private fun stopBackgroundThread() {
        mBackgroundThreadHandler!!.quitSafely()
        try {
            mBackgroundThreadHandler!!.join()
            mBackgroundThreadHandler = null
            mBackgroundHandler = null
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
    }

    private fun takePicture() {
        if (mCameraDevice == null) {
            Log.e(TAG, "cameraDevice is null")
            return
        }
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = manager.getCameraCharacteristics(
                mCameraDevice!!.id
            )
            var jpegSizes: Array<Size>? = null
            if (characteristics != null) {
                jpegSizes =
                    characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
                        .getOutputSizes(ImageFormat.JPEG)
            }
            var width = 640
            var height = 480
            if (!jpegSizes.isNullOrEmpty()) {
                width = jpegSizes[0].width
                height = jpegSizes[0].height
            }
            val reader: ImageReader = ImageReader.newInstance(width, height, ImageFormat.JPEG, 1)
            val outputSurfaces: MutableList<Surface> = ArrayList<Surface>(2)
            outputSurfaces.add(reader.surface)
            outputSurfaces.add(Surface(textureView!!.surfaceTexture))
            val captureBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(reader.surface)
            captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            // Orientation
            val rotation = windowManager.defaultDisplay.rotation
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS[rotation])
            val file = File(Environment.getExternalStorageDirectory().toString() + "/pic.jpg")
            val readerListener: ImageReader.OnImageAvailableListener =
                object : ImageReader.OnImageAvailableListener {
                    override fun onImageAvailable(reader: ImageReader) {
                        var image: Image? = null
                        try {
                            image = reader.acquireLatestImage()
                            val buffer: ByteBuffer = image.planes[0].buffer
                            val bytes = ByteArray(buffer.capacity())
                            buffer.get(bytes)
                            save(bytes)
                        } catch (e: FileNotFoundException) {
                            e.printStackTrace()
                        } catch (e: IOException) {
                            e.printStackTrace()
                        } finally {
                            image?.close()
                        }
                    }

                    @Throws(IOException::class)
                    private fun save(bytes: ByteArray) {
                        var output: OutputStream? = null
                        try {
                            output = FileOutputStream(file)
                            output!!.write(bytes)
                        } finally {
                            output?.close()
                        }
                    }
                }
            reader.setOnImageAvailableListener(readerListener, mBackgroundHandler)
            val captureListener: CameraCaptureSession.CaptureCallback =
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        super.onCaptureCompleted(session, request, result)
                        Toast.makeText(this@MainActivity, "Saved:$file", Toast.LENGTH_SHORT)
                            .show()
                        createCameraPreview()
                    }
                }
            mCameraDevice!!.createCaptureSession(
                outputSurfaces,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            session.capture(
                                captureBuilder.build(),
                                captureListener,
                                mBackgroundHandler
                            )
                        } catch (e: CameraAccessException) {
                            e.printStackTrace()
                        }
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {}
                },
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = textureView!!.surfaceTexture!!
            texture.setDefaultBufferSize(mImageDimension!!.getWidth(), mImageDimension!!.getHeight())
            val surface = Surface(texture)
            mCaptureRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder!!.addTarget(surface)
            mCameraDevice!!.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        //The camera is already closed
                        if (null == mCameraDevice) {
                            return
                        }
                        // When the session is ready, we start displaying the preview.
                        mCameraCaptureSessions = cameraCaptureSession
                        updatePreview()
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        Toast.makeText(
                            this@MainActivity,
                            "Configuration change",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                null
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        Log.e(TAG, "is camera open")
        try {
            mCameraId = manager.cameraIdList[0]
            val characteristics = manager.getCameraCharacteristics(mCameraId.toString())
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            mImageDimension = map.getOutputSizes(SurfaceTexture::class.java)[0]
            // Add permission for camera and let user grant the permission
            if (ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.CAMERA
                ) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf<String>(
                        android.Manifest.permission.CAMERA,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ),
                    REQUEST_CAMERA_PERMISSION
                )
                return
            }
            manager.openCamera(mCameraId!!, stateCallback, mBackgroundHandler)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        Log.e(TAG, "openCamera X")
    }

    private fun updatePreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error, return")
        }
        mCaptureRequestBuilder!!.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        try {
            mCameraCaptureSessions!!.setRepeatingRequest(
                mCaptureRequestBuilder!!.build(),
                null,
                mBackgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun closeCamera() {
        if ( mCameraDevice != null) {
            mCameraDevice!!.close()
            mCameraDevice = null
        }
        if (null != mImageReader) {
            mImageReader!!.close()
            mImageReader = null
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults[0] == PackageManager.PERMISSION_DENIED) {
                // close the app
                Toast.makeText(
                    this@MainActivity,
                    "Sorry!!!, you can't use this app without granting permission",
                    Toast.LENGTH_LONG
                ).show()
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        startBackgroundThread()
        if (textureView!!.isAvailable) {
            openCamera()
        } else {
            textureView!!.surfaceTextureListener = textureListener
        }
    }

    override fun onPause() {
        Log.e(TAG, "onPause")
        //closeCamera();
        stopBackgroundThread()
        super.onPause()
    }

}


