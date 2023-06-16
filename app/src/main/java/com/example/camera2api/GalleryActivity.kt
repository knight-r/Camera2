package com.example.camera2api

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.example.camera2api.databinding.ActivityGalleryBinding
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.util.MimeTypes
import java.io.File
import java.io.Serializable

class GalleryActivity : AppCompatActivity(), OnItemClickListener, View.OnClickListener {
    private lateinit var galleryBinding: ActivityGalleryBinding
    private var player: ExoPlayer? = null
    private var mIsVideo: Boolean = false
    private lateinit var currentSelectedMedia: File
    private var currentMediaPosition: Int = 0
    private lateinit var mediaFileList: MutableList<File>
    private lateinit var updatedFileList: MutableList<File>
    private lateinit var deletedFileList: MutableList<File>
    private lateinit var mAdapter: MediaListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        galleryBinding = ActivityGalleryBinding.inflate(layoutInflater)
        setContentView(galleryBinding.root)

        val bundle: Bundle? = intent.extras
        mediaFileList = bundle?.getSerializable("media_list") as MutableList<File>
        Log.e("Gallery", mediaFileList.size.toString())
        updatedFileList = mutableListOf()
        deletedFileList = mutableListOf()
        updatedFileList.addAll(mediaFileList)
        if(updatedFileList.isNotEmpty()) {
            val mediaFile = updatedFileList[0]
            updateViewerUI(mediaFile)
        }
        mAdapter = MediaListAdapter(updatedFileList, galleryBinding.ivFullScreenMedia,galleryBinding.playerView)
        galleryBinding.rvImageList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = mAdapter
            (adapter as MediaListAdapter).setOnItemClickListener(this@GalleryActivity)
            currentSelectedMedia = updatedFileList[0]
            currentMediaPosition = 0
        }
        galleryBinding.rvImageList.layoutManager = LinearLayoutManager(
            this,
            LinearLayoutManager.HORIZONTAL,
            false
        )

       initOnClickListener()
    }

    private fun initOnClickListener() {
        galleryBinding.apply {
            ivClose.setOnClickListener(this@GalleryActivity)
            ivDelete.setOnClickListener(this@GalleryActivity)
            ivSave.setOnClickListener(this@GalleryActivity)
            ivRotateLeft.setOnClickListener(this@GalleryActivity)
            ivRotateRight.setOnClickListener(this@GalleryActivity)
        }

    }
    override fun onClick(v: View?) {
        when(v!!.id) {
            galleryBinding.ivClose.id -> {
                processSavedData(true)
            }
            galleryBinding.ivDelete.id -> {
                if(updatedFileList.size<=1) {
                // If he deleted all the files without saving any then cancel it.
                     processSavedData(true)
                } else {
                     proceedDelete()
               }
            }
            galleryBinding.ivSave.id -> {
                clearDeletedFiles(deletedFileList)
                deletedFileList.clear()
                processSavedData(false)
            }
        }
    }

    private fun proceedDelete() {
        updatedFileList.remove(currentSelectedMedia)
        deletedFileList.add(currentSelectedMedia)
        currentMediaPosition =
            if (mediaFileList.size > currentMediaPosition) currentMediaPosition - 1 else 0
        mAdapter.updateList(updatedFileList, currentMediaPosition)
        currentSelectedMedia =
            updatedFileList[if (currentMediaPosition < 0) 0 else currentMediaPosition]
        updateViewerUI(currentSelectedMedia)
    }
    private fun updateViewerUI(mediaFile: File) {
        if(isImageFile(mediaFile.absolutePath)) {
            val bitMap = BitmapFactory.decodeFile(mediaFile.absolutePath)
            galleryBinding.ivFullScreenMedia.setImageBitmap(bitMap)
            galleryBinding.playerView.visibility = View.GONE
            galleryBinding.ivFullScreenMedia.visibility = View.VISIBLE

        } else if(isVideoFile(mediaFile.absolutePath)){
            mIsVideo = true
            //galleryBinding.ivFullScreenMedia.visibility = View.GONE
            Glide.with(this)
                .setDefaultRequestOptions(RequestOptions().frame(1000000)) // capture a frame from 1 second into the video
                .load(mediaFile.toUri())
                .into(galleryBinding.ivFullScreenMedia)
            galleryBinding.ivFullScreenMedia.visibility = View.GONE
            galleryBinding.playerView.visibility = View.VISIBLE
            initializePlayer(mediaFile.toUri())
        }
    }

    private fun processSavedData(isClosed: Boolean) {
        var returnIntent = Intent()
        val bundle = Bundle()
        bundle.putSerializable("media_list",(if (isClosed) mediaFileList else updatedFileList) as Serializable)
        returnIntent.putExtras(bundle)
        setResult(Activity.RESULT_OK, returnIntent)
        finish()
    }

    /**
     * this will initiate the video player in activity
     */
    private fun initializePlayer(videoUri: Uri) {
        player = ExoPlayer.Builder(this) // <- context
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setMimeType(
                MimeTypes.APPLICATION_MP4)
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(this) // <- context
        )
            .createMediaSource(mediaItem)

        player!!.apply {
            setMediaSource(mediaSource)
            playWhenReady = true // start playing when the exoplayer has setup
            seekTo(0, 0L) // Start from the beginning
            prepare() // Change the state from idle.
        }.also {

            galleryBinding.playerView.player = it
        }
    }
    private fun isImageFile(filePath: String): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(filePath)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase())
        return mimeType != null && mimeType.startsWith("image/")
    }

    private fun isVideoFile(filePath: String): Boolean {
        val extension = MimeTypeMap.getFileExtensionFromUrl(filePath)
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase())
        return mimeType != null && mimeType.startsWith("video/")
    }

    override fun onPause() {
        super.onPause()
        player?.stop()
    }

    override fun onStop() {
        super.onStop()
        player?.stop()
    }

    override fun onItemClick(position: Int) {
        currentMediaPosition = position
        if(position>=0 && position<updatedFileList.size) {
            currentSelectedMedia = updatedFileList[position]
        }
        mAdapter.updateList(updatedFileList, currentMediaPosition)
        updateViewerUI(currentSelectedMedia)
    }

    private fun clearDeletedFiles(fileList: List<File>) {
        fileList?.forEach { file ->
            if(file.exists()) {
                file.delete()
            }
        }
    }


}