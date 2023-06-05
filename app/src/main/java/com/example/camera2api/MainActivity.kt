package com.example.camera2api

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
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
import android.widget.Chronometer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.camera2api.databinding.ActivityMainBinding
import com.otaliastudios.transcoder.Transcoder
import com.otaliastudios.transcoder.TranscoderListener
import com.otaliastudios.transcoder.TranscoderOptions
import com.otaliastudios.transcoder.common.TrackStatus
import com.otaliastudios.transcoder.strategy.DefaultAudioStrategy
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.validator.DefaultValidator
import org.mp4parser.muxer.Movie
import org.mp4parser.muxer.Track
import org.mp4parser.muxer.builder.DefaultMp4Builder
import org.mp4parser.muxer.container.mp4.MovieCreator
import org.mp4parser.muxer.tracks.AppendTrack
import java.io.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Future


open class MainActivity : AppCompatActivity() {

    private var mTextureView: TextureView? = null
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
    private lateinit var mImageFileName: File
    private lateinit var mImageFolder: File
    private lateinit var mVideoFolder: File
    private lateinit var mVideoFileName: String
    private var mChronometer: Chronometer? = null
    private var mTotalRotation: Int = 0
    private var   isCameraSwitched:Boolean = false
    private var isVideoPaused: Boolean = false
    private lateinit var progressDialogue: ProgressDialog
    private var mVideoFileList = mutableListOf<String>()

    private var mIsOddNumberVideo = true
    private lateinit var file: File
    private var textureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
            setupCamera(w,h)
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

    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened")
            mCameraDevice = camera
            createCameraPreview()

        }

        override fun onDisconnected(camera: CameraDevice) {
           mCameraDevice?.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera?.close()
            mCameraDevice = null
        }
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(mainBinding.root)
        mTextureView = mainBinding.textureView
        assert(mTextureView != null)
        progressDialogue = ProgressDialog(this)
        mChronometer = mainBinding.chronometer
        mChronometer!!.visibility = View.GONE

        mCameraId = ID_CAMERA_BACK
        mTextureView!!.surfaceTextureListener = textureListener

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
        checkPermissions()
        createVideoFolder()
        createImageFolder()

        mainBinding.chronometer.visibility = View.GONE
        mainBinding.ivTakePicture.setOnClickListener {
            takePicture()
        }

        mainBinding.ivStartStop.setOnClickListener {
            if (mIsRecordingVideo ) {
                mIsRecordingVideo = false
                mainBinding.ivStartStop.setBackgroundResource(R.drawable.ic_start_video_icon)
                stopRecordingVideo()
                closeCamera()
                setupCamera(mPreviewSize.width,mPreviewSize.height)
                openCamera()

            } else {
                mIsRecordingVideo = true
                mainBinding.ivStartStop.setBackgroundResource(R.drawable.ic_stop)
                startRecordingVideo()

            }
        }

        mainBinding.ivSwitchCamera.setOnClickListener {
            isCameraSwitched = true
            mCameraId = if(mCameraId == ID_CAMERA_FRONT){
                ID_CAMERA_BACK
            } else {
                ID_CAMERA_FRONT
            }
            if(mIsRecordingVideo) {
                stopRecordingVideo()
                closeCamera()
                setupCamera(mPreviewSize.width,mPreviewSize.height)
                openCamera()

                startRecordingVideo()

            } else {
                closeCamera()
                setupCamera(mPreviewSize.width,mPreviewSize.height)
                openCamera()
            }
        }
        var timeWhenPaused: Long = 0
        mainBinding.ivPauseResume.setOnClickListener {
            if (isVideoPaused) {
                isVideoPaused = false
                mainBinding.ivPauseResume.setImageResource(R.drawable.ic_pause_icon)
                mChronometer!!.base = SystemClock.elapsedRealtime() + timeWhenPaused
                mChronometer!!.start()
                mMediaRecorder.resume()
            } else {
                isVideoPaused = true
                mainBinding.ivPauseResume.setImageResource(R.drawable.ic_resume_icon)
                timeWhenPaused = mChronometer!!.base - SystemClock.elapsedRealtime()
                mChronometer!!.stop()
                mMediaRecorder.pause()
            }
        }
    }

    /**
     * setup camera to take picture
     */
    private fun setupCamera(width: Int, height: Int) {
        val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {

                val cameraCharacteristics = cameraManager.getCameraCharacteristics(mCameraId.toString())

                val map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val deviceOrientation = windowManager.defaultDisplay.rotation
                mTotalRotation = sensorToDeviceRotation(cameraCharacteristics, deviceOrientation)
                val swapRotation: Boolean = (mTotalRotation == 90 || mTotalRotation == 270)
                val largestSize = Collections.max(listOf(*map!!.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea())
                mPreviewSize = largestSize
                var rotatedWidth: Int = largestSize.width
                var rotatedHeight: Int = largestSize.height
                if(swapRotation) {
                     rotatedWidth = largestSize.width
                     rotatedHeight = largestSize.width
                }


                // Create an ImageReader to capture images
                mImageReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, 1)
                mImageReader!!.setOnImageAvailableListener({ reader ->
                    Toast.makeText(this@MainActivity, "Image saved", Toast.LENGTH_SHORT).show()
                    val image = reader!!.acquireLatestImage()

                    var byteBuffer = image.planes[0].buffer
                    var byteArray = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(byteArray)

                    val outPutStream = FileOutputStream(mImageFileName)
                    outPutStream.write(byteArray)
                    outPutStream.close()

                    image.close()
                }, mBackgroundHandler)

        } catch (e: Exception) {
           Log.e(TAG, e.message.toString())
        }
     }

    private fun mergeVideosUsingMp4Parser(videoFiles: List<String>, outputFile: File) {
        try {
            progressDialogue.setMessage("Merging Videos..")
            progressDialogue.show()
            val movieList = mutableListOf<Movie>()
            for (filePath  in videoFiles) {
                val movie = MovieCreator.build(filePath)
                movieList.add(movie)
            }
            val videoTracks = mutableListOf<Track>()
            val audioTracks = mutableListOf<Track>()

            for (movie in movieList) {
                for (track in movie.tracks) {
                    if (track.handler == "vide") {
                        videoTracks.add(track)
                    }
                    if (track.handler == "soun") {
                        audioTracks.add(track)
                    }
                }
            }

            val mergedMovie = Movie()
            if (videoTracks.size > 0) {
                mergedMovie.addTrack(AppendTrack(*videoTracks.toTypedArray()))
            }

            if (audioTracks.size > 0) {
                mergedMovie.addTrack(AppendTrack(*audioTracks.toTypedArray()))
            }

            val container = DefaultMp4Builder().build(mergedMovie)
            val fileChannel = FileOutputStream(outputFile.absolutePath).channel
            container.writeContainer(fileChannel)
            fileChannel.close()
            progressDialogue.cancel()

            Toast.makeText(this, "Videos merged successfully", Toast.LENGTH_SHORT).show()

        } catch (e : Exception) {
            Log.e(TAG, e.message.toString())
        }
    }

    private fun mergeVideosUsingTranscoder(videoFiles: List<String>, outputFile: File) {
        progressDialogue.setMessage("Merging Videos..")
        progressDialogue.show()
        val builder: TranscoderOptions.Builder =
            Transcoder.into(outputFile.absolutePath)
        for(path in videoFiles) {
            builder.addDataSource(path)
        }

        // use DefaultVideoStrategy.exact(2560, 1440).build()  to restore 75% size of the video
        //  use DefaultVideoStrategy.exact(mScreenSize.height, mScreenSize.width).build()  to restore 50% size of the video
        val strategy: DefaultVideoStrategy = DefaultVideoStrategy.exact(640, 480).build()

         var mTranscodeFuture: Future<Void>? = builder
            .setAudioTrackStrategy(DefaultAudioStrategy.builder().build())
            .setVideoTrackStrategy(strategy)
            .setVideoRotation(0)
            .setListener(object : TranscoderListener {

                override fun onTranscodeProgress(progress: Double) {}

                override fun onTranscodeCompleted(successCode: Int) {
                    progressDialogue.cancel()
                    Toast.makeText(this@MainActivity, "Video Merged Successfully", Toast.LENGTH_SHORT).show()
                }

                override fun onTranscodeCanceled() {
                    Toast.makeText(this@MainActivity, "Video rotation cancelled", Toast.LENGTH_SHORT).show()
                }

                override fun onTranscodeFailed(exception: Throwable) {
                    Log.e(TAG, exception.message.toString())
                }

            })
            .setValidator(object : DefaultValidator() {
                override fun validate(videoStatus: TrackStatus, audioStatus: TrackStatus): Boolean {
                    //  mIsAudioOnly = !videoStatus.isTranscoding
                    return super.validate(videoStatus, audioStatus)
                }

            }).transcode()

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
             return java.lang.Long.signum((lhs!!.width * lhs.height).toLong() - (rhs!!.width * rhs.height).toLong())
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
        ) {

        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult,
        ) { }
    }

    private fun startRecordingVideo() {
        try {
            mMediaRecorder = MediaRecorder()
            mIsRecordingVideo = true
            createVideoFileName()
            mChronometer!!.base = SystemClock.elapsedRealtime()
            mChronometer!!.visibility = View.VISIBLE

            mChronometer!!.start();
            setUpMediaRecorder()
            mMediaRecorder.start()

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
    private fun stopRecordingVideo() {
        mIsOddNumberVideo = true
        mChronometer!!.stop()
        mChronometer!!.base = SystemClock.elapsedRealtime();
        mChronometer!!.visibility = View.GONE
        mMediaRecorder.reset()
        val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
        sendBroadcast(mediaStoreUpdateIntent)
        Toast.makeText(this, "Video saved to file explorer", Toast.LENGTH_SHORT).show()
        mVideoFileList.add(mVideoFileName)
        if (!mIsRecordingVideo) {
            val prepend = "Merged_VIDEO"
            val outputFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
           // mergeVideosUsingMp4Parser(mVideoFileList, outputFile)
            mergeVideosUsingTranscoder(mVideoFileList,outputFile)
            mVideoFileList = mutableListOf<String>()
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
                setVideoSize(640,480) // (mScreenSize.width, mScreenSize.height)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                if(mCameraId == ID_CAMERA_FRONT) {
                    setOrientationHint(270)
                } else {
                    setOrientationHint(90)
                }

                prepare()
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to set up MediaRecorder: ${e.message}")
        }
    }



    private fun takePicture() {
        try {
            mImageFileName = createImageFileName()
            mCaptureRequestBuilder = mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            mCaptureRequestBuilder!!.addTarget(mImageReader!!.surface)
            mCameraCaptureSessions!!.capture(mCaptureRequestBuilder!!.build(),null ,null)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
    }

    private fun createCameraPreview() {
        try {
            val texture = mTextureView!!.surfaceTexture!!
            texture.setDefaultBufferSize(mImageDimension!!.width, mImageDimension!!.height)
            val surface = Surface(texture)
            mCaptureRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            mCaptureRequestBuilder!!.addTarget(surface)
            mCameraDevice!!.createCaptureSession(
                listOf(surface,mImageReader!!.surface),
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

            isCameraSwitched = false
            val characteristics = manager.getCameraCharacteristics(mCameraId.toString())
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            assert(map != null)
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
            manager.openCamera(mCameraId.toString(), stateCallback, mBackgroundHandler)

        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        Log.e(TAG, "openCamera X")
    }

    private fun updatePreview() {
        if (null == mCameraDevice) {
            Log.e(TAG, "updatePreview error")
            return
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

    /**
     * check permissions to access camera hardware
     */
    private  fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            == PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
           // Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
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

    private fun createImageFileName(): File {
        val timestamp: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())
        val prepend = "IMAGE_" + timestamp + "_"
        return File.createTempFile(prepend, ".jpg", mImageFolder)
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

    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        startBackgroundThread()
        if (mTextureView!!.isAvailable) {
           openCamera()
        } else {
            mTextureView!!.surfaceTextureListener = textureListener
        }

    }

    override fun onPause() {
        Log.e(TAG, "onPause")
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    companion object {
        const val ID_CAMERA_FRONT = "1"
        const val ID_CAMERA_BACK = "0"
        const val TAG = "Camera2"
    }
    class CompareSizesByArea : Comparator<Size> {
        override fun compare(lhs: Size, rhs: Size): Int {
            return java.lang.Long.signum(lhs.width.toLong() * lhs.height - rhs.width.toLong() * rhs.height)
        }
    }

}



