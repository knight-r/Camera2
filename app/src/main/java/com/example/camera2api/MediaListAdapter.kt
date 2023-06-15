package com.example.camera2api

import android.annotation.SuppressLint
import android.app.PendingIntent.getActivity
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.net.VpnService.prepare
import android.provider.SyncStateContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.core.net.toUri
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.source.ProgressiveMediaSource
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.google.android.exoplayer2.upstream.DefaultDataSource
import com.google.android.exoplayer2.util.MimeTypes
import java.io.File


class MediaListAdapter(private var mList:List<File>, private val imageView: ImageView, private val playerView: StyledPlayerView): RecyclerView.Adapter<MediaListAdapter.ViewHolder>() {
    private lateinit var _context: Context
    private  var selectedPosition: Int = 0
    private var player: ExoPlayer? = null
    private var mListener: OnItemClickListener? = null
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        _context = parent.context
        val view: View = LayoutInflater.from(parent.context).inflate(R.layout.media_item,parent,false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, @SuppressLint("RecyclerView") position: Int) {
        val mediaFile  = mList[position]

      //  holder.imageView.setImageBitmap(bitMap)
        if(isImageFile(mediaFile.absolutePath)) {
            val bitMap = BitmapFactory.decodeFile(mediaFile.absolutePath)
            holder.imageView.setImageBitmap(bitMap)
        } else if(isVideoFile(mediaFile.absolutePath)){
            Glide.with(holder.itemView.context)
                .setDefaultRequestOptions( RequestOptions().frame(1000000)) // capture a frame from 1 second into the video
                .load(mediaFile.toUri())
                .into(holder.imageView)
        }

        holder.relativeLayout.setBackgroundResource( if (selectedPosition == position) R.drawable.selected_image_background else R.drawable.non_selected_image_background)
        holder.itemView.setOnClickListener {
            mListener?.onItemClick(position)
            selectedPosition = position
        }

    }
    override fun getItemCount(): Int {
        return mList.size
    }

    class ViewHolder(ItemView: View) : RecyclerView.ViewHolder(ItemView) {
        val imageView: ImageView = itemView.findViewById(R.id.iv_media_item)
        val relativeLayout: RelativeLayout = itemView.findViewById(R.id.rl_media_item)

    }

    /**
     * this will initiate the video player in activity
     */
    private fun initializePlayer(videoUri: Uri) {
         player = ExoPlayer.Builder(_context) // <- context
            .build()
        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setMimeType(
                MimeTypes.APPLICATION_MP4)
            .build()
        val mediaSource = ProgressiveMediaSource.Factory(
            DefaultDataSource.Factory(_context) // <- context
        )
            .createMediaSource(mediaItem)

        player!!.apply {
            setMediaSource(mediaSource)
            playWhenReady = true // start playing when the exoplayer has setup
            seekTo(0, 0L) // Start from the beginning
            prepare() // Change the state from idle.
        }.also {

            playerView.player = it
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

    fun setOnItemClickListener(listener: OnItemClickListener) {
        mListener = listener
    }

    fun updateList(dataList:List<File>, position: Int) {
        this.mList = dataList
        this.selectedPosition = if (position<0) 0 else position
        notifyDataSetChanged()
    }

}