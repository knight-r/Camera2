package com.example.camera2api

import android.app.ProgressDialog
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.Display
import android.view.OrientationEventListener
import android.view.Surface
import android.view.TextureView
import android.view.TextureView.SurfaceTextureListener
import android.view.View
import android.widget.Chronometer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
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


open class MainActivity : AppCompatActivity(), View.OnClickListener {

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
    private lateinit var mPreviewSize: Size
    private lateinit var mImageFileName: File
    private lateinit var mImageFolder: File
    private lateinit var mVideoFolder: File
    private lateinit var mVideoFileName: String
    private var mChronometer: Chronometer? = null
    private var mTotalRotation: Int = 0
    private var isCameraSwitched:Boolean = false
    private lateinit var progressDialogue: ProgressDialog
    private var mVideoFileList = mutableListOf<String>()
    private var mWidth: Int =0
    private var mHeight: Int =0
    private var mIsVideoCaptureOn: Boolean = false
    private var mIsFlashOn: Boolean = false
    private var mIsVideoPaused: Boolean = false
    private lateinit var mCameraManager : CameraManager
    private lateinit var mSurface: Surface
    private lateinit var mMediaFileList: MutableList<File>
    private var REQUEST_CODE_GALLERY: Int = 1001
    private lateinit var mCameraCharacteristics: CameraCharacteristics
    private val displayManager: DisplayManager by lazy {
        applicationContext.getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    /** Keeps track of display rotations */
    private var displayRotation = 0

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displayManager.registerDisplayListener(displayListener,mBackgroundHandler)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        displayManager.unregisterDisplayListener(displayListener)
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            val difference = displayManager.getDisplay(displayId).rotation - displayRotation
            displayRotation = displayManager.getDisplay(displayId).rotation

            if (difference == 2 || difference == -2) {
                createCameraPreview()
            }
        }
    }
    private var textureListener: SurfaceTextureListener = object : SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, w: Int, h: Int) {
            setupCamera(w,h)
            openCamera()
        }

        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
            // Transform you image captured size according to the surface width and height
            setupCamera(width,height)
            openCamera()
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture) = false

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
    }

    private val stateCallback: CameraDevice.StateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            //This is called when the camera is open
            Log.e(TAG, "onOpened")
            mCameraDevice = camera
            try {
                mBackgroundHandler!!.post {
                    if (mTextureView!!.isAvailable) {
                        createCameraPreview()
                    }
                    mTextureView!!.surfaceTextureListener = textureListener
                }
            } catch (t: Throwable) {
                closeCamera()
                Log.e(TAG, "Failed to initialize camera.", t)
            }


        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
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
        mMediaFileList = mutableListOf()
        mChronometer = mainBinding.chronometer
        mChronometer!!.visibility = View.GONE

        mCameraId = ID_CAMERA_BACK
        mTextureView!!.surfaceTextureListener = textureListener
        mCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

        ORIENTATIONS.append(Surface.ROTATION_0, 90)
        ORIENTATIONS.append(Surface.ROTATION_90, 0)
        ORIENTATIONS.append(Surface.ROTATION_180, 270)
        ORIENTATIONS.append(Surface.ROTATION_270, 180)
        checkPermissions()
        createVideoFolder()
        createImageFolder()

        mainBinding.chronometer.visibility = View.GONE
        mainBinding.tvPause.visibility= View.GONE
        mainBinding.rlPauseResumeStop.visibility = View.GONE
        initOnClickListeners()
    }

    /**
     *  all button clickListeners are implemented here
     */
    private fun initOnClickListeners() {
        mainBinding.tvVideo.setOnClickListener(this)
        mainBinding.tvCamera.setOnClickListener(this)
        mainBinding.ibCapturePhoto.setOnClickListener(this)
        mainBinding.tv1xZoom.setOnClickListener(this)
        mainBinding.tv2xZoom.setOnClickListener(this)
        mainBinding.ivFlash.setOnClickListener(this)
        mainBinding.ibPauseResume.setOnClickListener(this)
        mainBinding.ibStop.setOnClickListener(this)
        mainBinding.ivPhotoGallery.setOnClickListener(this)
        mainBinding.ivSwitchCamera.setOnClickListener(this)
    }

    /**
     *  onClick functionalities are implemented here
     */
    override fun onClick(view: View?) {
        when(view?.id ) {
            mainBinding.tvVideo.id -> {
                mIsVideoCaptureOn = true
                mainBinding.ibCapturePhoto.setImageResource(R.drawable.ic_capture_video)

                mainBinding.apply {
                    rlTools.visibility = View.GONE
                    tvVideo.setBackgroundResource(R.drawable.selected_background)
                    tvVideo.setTextColor(Color.BLACK)
                    tvCamera.setBackgroundResource(R.drawable.non_selected_background)
                    tvCamera.setTextColor(Color.WHITE)

                }
            }

            mainBinding.tvCamera.id -> {
                mIsVideoCaptureOn = false

                if (mIsRecordingVideo) {
                    stopRecordingVideo()
                }
                mIsRecordingVideo = false
                mainBinding.apply {
                    ibPauseResume.setImageResource(R.drawable.ic_pause)
                    ivPhotoGallery.setImageResource(R.drawable.ic_photo_gallery)
                    rlPauseResumeStop.visibility = View.GONE
                    tvPause.visibility = View.GONE
                    ibCapturePhoto.visibility = View.VISIBLE
                    ibCapturePhoto.setImageResource(R.drawable.ic_capture_photo)
                    chronometer.visibility = View.GONE
                    rlTools.visibility = View.VISIBLE
                    tvCamera.setBackgroundResource(R.drawable.selected_background)
                    tvCamera.setTextColor(Color.BLACK)
                    tvVideo.setBackgroundResource(R.drawable.non_selected_background)
                    tvVideo.setTextColor(Color.WHITE)

                }

            }
            mainBinding.ibCapturePhoto.id -> {
                if(mIsVideoCaptureOn) {
                    startRecordingVideo()
                } else {
//                    Toast.makeText(this, "capture started", Toast.LENGTH_SHORT).show()
                    takePicture()

                }
            }
            mainBinding.tv1xZoom.id  -> {
                mainBinding.apply {
                    tv1xZoom.setBackgroundResource(R.drawable.selected_background)
                    tv1xZoom.setTextColor(Color.BLACK)
                    tv2xZoom.setBackgroundResource(R.drawable.non_selected_background)
                    tv2xZoom.setTextColor(Color.WHITE)
                }
            }
            mainBinding.tv2xZoom.id -> {
                mainBinding.apply {
                    tv2xZoom.setBackgroundResource(R.drawable.selected_background)
                    tv2xZoom.setTextColor(Color.BLACK)
                    tv1xZoom.setBackgroundResource(R.drawable.non_selected_background)
                    tv1xZoom.setTextColor(Color.WHITE)
                }
            }

            mainBinding.ivFlash.id -> {
               // implement on/off of flash
            }
            mainBinding.ibPauseResume.id -> {
                if (mIsVideoPaused) {
                    mIsVideoPaused = false
                    mainBinding.ibPauseResume.setImageResource(R.drawable.ic_pause)
                    mainBinding.chronometer.visibility = View.VISIBLE
                    mainBinding.tvPause.visibility = View.GONE
                    mMediaRecorder.resume()

                } else {
                    mIsVideoPaused = true
                    mainBinding.ibPauseResume.setImageResource(R.drawable.ic_play)
                    mainBinding.chronometer.visibility = View.GONE
                    mainBinding.tvPause.visibility = View.VISIBLE
                    mMediaRecorder.pause()
                }
            }

            mainBinding.ibStop.id -> {
                mainBinding.apply {
                    rlPauseResumeStop.visibility = View.GONE
                    ibCapturePhoto.visibility = View.VISIBLE
                    chronometer.visibility = View.GONE
                    ivPhotoGallery.setImageResource(R.drawable.ic_photo_gallery)
                    tvPause.visibility = View.GONE
                    ivPhotoGallery.setImageResource(R.drawable.ic_photo_gallery)
                }
                mIsRecordingVideo = false
                stopRecordingVideo()
                closeCamera()
                setupCamera(mWidth,mHeight)
                openCamera()


            }
            mainBinding.ivPhotoGallery.id -> {
                if( mIsRecordingVideo) {
                    takePicture()
                } else {
                    Log.e(TAG, "calling gallery activity")
                    startGalleryActivity()
                }
            }

            mainBinding.ivSwitchCamera.id -> {
                isCameraSwitched = true
                mCameraId = if(mCameraId == ID_CAMERA_FRONT){
                    ID_CAMERA_BACK
                } else {
                    ID_CAMERA_FRONT
                }
                if(mIsRecordingVideo) {
                    stopRecordingVideo()
                    closeCamera()
                    setupCamera(mWidth,mHeight)
                    openCamera()

                    startRecordingVideo()

                } else {
                    closeCamera()
                    setupCamera(mWidth,mHeight)
                    openCamera()
                }
            }

        }

    }

    private fun startGalleryActivity() {
//        Toast.makeText(this, mMediaFileList.size.toString(), Toast.LENGTH_SHORT).show()

        if (mMediaFileList.size > 0) {
            val intent = Intent(this, GalleryActivity::class.java)
            val bundle = Bundle()
            bundle.putSerializable("media_list",mMediaFileList as Serializable)
            intent.putExtras(bundle)
            startActivityForResult(intent, REQUEST_CODE_GALLERY)
        } else {
            Toast.makeText(this, "No media captured", Toast.LENGTH_SHORT).show()
        }

    }


    /**
     * setup camera to take picture
     */
    private fun setupCamera(width: Int, height: Int) {
        try {

           mCameraCharacteristics = mCameraManager.getCameraCharacteristics(mCameraId.toString())
            val map = mCameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val deviceOrientation = windowManager.defaultDisplay.rotation
            sensorToDeviceRotation(mCameraCharacteristics, deviceOrientation)
            mTotalRotation = ORIENTATIONS[deviceOrientation]
            val swapRotation: Boolean = (mTotalRotation == 90 || mTotalRotation == 270)
            val largestSize = Collections.max(listOf(*map!!.getOutputSizes(ImageFormat.JPEG)), CompareSizesByArea())

            mPreviewSize = largestSize

            // Create an ImageReader to capture images
            mImageReader = ImageReader.newInstance(largestSize.width, largestSize.height, ImageFormat.JPEG, 1)
            mImageReader!!.setOnImageAvailableListener({ reader ->
                Toast.makeText(this@MainActivity, "Image saved", Toast.LENGTH_SHORT).show()
                val image = reader!!.acquireLatestImage()
                val byteBuffer = image.planes[0].buffer
                val byteArray = ByteArray(byteBuffer.remaining())
                byteBuffer.get(byteArray)
                val outPutStream = FileOutputStream(mImageFileName)
                if(mImageFileName.exists()) {
                    mMediaFileList.add(mImageFileName)
                }

                val bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)
                val matrix = Matrix()
                val sensorOrientation = mCameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                matrix.postRotate(sensorOrientation.toFloat())

                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                mainBinding.ivPhotoGallery.post {
                    mainBinding.ivPhotoGallery.setImageBitmap(rotatedBitmap)
                }
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, outPutStream)
                outPutStream.close()



                image.close()
            }, mBackgroundHandler)

        } catch (e: Exception) {
            Log.e(TAG, e.message.toString())
        }
    }

    private fun saveImageData(bytes: ByteArray) {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val contentResolver = contentResolver
        val contentValues = ContentValues()
        contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, "tekion_" + Date().time + ".jpg")
        contentValues.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")

// Save the image bytes to the gallery directory
        val imageUri: Uri? =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        try {
            val outputStream: OutputStream? = contentResolver.openOutputStream(imageUri!!)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
            outputStream?.close()
        } catch (e: IOException) {
            e.printStackTrace()
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


        val strategy: DefaultVideoStrategy = DefaultVideoStrategy.exact(1920, 1080).build()
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

    private fun startRecordingVideo() {
        try {
            mainBinding.apply {
                chronometer.visibility = View.VISIBLE
                ibCapturePhoto.visibility = View.GONE
                rlPauseResumeStop.visibility = View.VISIBLE
                ivPhotoGallery.setImageResource(R.drawable.ic_camera)
            }
            mIsRecordingVideo = true
            mMediaRecorder = MediaRecorder()
            mIsRecordingVideo = true
            createVideoFileName()
            mChronometer!!.base = SystemClock.elapsedRealtime()

            mChronometer!!.start()
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
        mChronometer!!.stop()
        mChronometer!!.base = SystemClock.elapsedRealtime();
        mChronometer!!.visibility = View.GONE
        mMediaRecorder.reset()
        val mediaStoreUpdateIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
        mediaStoreUpdateIntent.data = Uri.fromFile(File(mVideoFileName))
        sendBroadcast(mediaStoreUpdateIntent)
        Toast.makeText(this, "Video saved to file explorer", Toast.LENGTH_SHORT).show()
        mVideoFileList.add(mVideoFileName)
        val videoFile = File(mVideoFileName)
        if(videoFile.exists()) {
            Glide.with(this)
                .setDefaultRequestOptions( RequestOptions().frame(1000000)) // capture a frame from 1 second into the video
                .load(videoFile.toUri())
                .into(mainBinding.ivPhotoGallery)
            mMediaFileList.add(videoFile)
        }
        if (!mIsRecordingVideo) {
            val prepend = "Merged_VIDEO"
            val outputFile = File.createTempFile(prepend, ".mp4", mVideoFolder)
            mMediaFileList.add(outputFile)
            // mergeVideosUsingMp4Parser(mVideoFileList, outputFile)
            if(mVideoFileList.size > 1) {
                mergeVideosUsingTranscoder(mVideoFileList,outputFile)
            }

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
                setVideoEncodingBitRate(10000000)
                setVideoFrameRate(30)
                setVideoSize(1920,1080) // (mScreenSize.width, mScreenSize.height)
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
            Log.e(TAG, e.message.toString())
        } catch (e1 : java.lang.Exception) {
            Log.e(TAG, e1.message.toString())
        }
    }

    private fun createCameraPreview() {
        try {

            val surfaceTexture = CameraUtils.buildTargetTexture(mTextureView!!,mCameraManager.getCameraCharacteristics(mCameraId!!),displayManager.getDisplay(
                Display.DEFAULT_DISPLAY).rotation)
            mSurface = Surface(surfaceTexture)
            mCaptureRequestBuilder =
                mCameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            mCameraDevice!!.createCaptureSession(
                listOf(mSurface, mImageReader!!.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        try {
                            mCameraCaptureSessions = cameraCaptureSession
                            mCaptureRequestBuilder = mCameraDevice?.createCaptureRequest(
                                CameraDevice.TEMPLATE_STILL_CAPTURE
                            )
                            mCaptureRequestBuilder?.addTarget(mSurface)
                            mCameraCaptureSessions!!.setRepeatingRequest(
                                mCaptureRequestBuilder?.build()!!,null, null
                            )
                            updatePreview()
                        } catch (t: Throwable) {
                            Log.e(TAG, "Failed to open camera preview.", t)
                        }
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

        Log.e(TAG, "is camera open")
        try {

            isCameraSwitched = false
            val characteristics = mCameraManager.getCameraCharacteristics(mCameraId.toString())
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
            mCameraManager.openCamera(mCameraId.toString(), stateCallback, mBackgroundHandler)

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
            Log.e(TAG, e.message.toString())
        } catch (e1: java.lang.Exception) {
            Log.e(TAG, e1.message.toString())
        }
    }

    private fun closeCamera() {
        try {
            mSurface.release()
            mCameraDevice!!.close()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to release resources.", t)
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
    ) {
        val sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)

        val rotationCompensation = (sensorOrientation!! - deviceOrientation  + 270) % 360

        val matrix = Matrix()
        matrix.postRotate(rotationCompensation.toFloat())
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode== RESULT_OK) {
            when(requestCode) {
                REQUEST_CODE_GALLERY -> {
                    mMediaFileList = mutableListOf()
                    val bundle: Bundle? = data?.extras
                    var tempMediaFileList = bundle?.getSerializable("media_list") as MutableList<File>
                    mMediaFileList.addAll(tempMediaFileList)
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        Log.e(TAG, "onResume")
        startBackgroundThread()
        if (mTextureView!!.isAvailable) {
            setupCamera(mWidth,mHeight)
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
        const val MAX_PREVIEW_WIDTH = 1920
        const val MAX_PREVIEW_HEIGHT = 1080
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