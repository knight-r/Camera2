package com.example.camera2api

import android.content.Intent
import android.media.Image
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class ImageSaver(acquiredImage: Image?, imageFileName:String): Runnable {
     var mImage: Image
     var mFileName: String

   init {
       mImage = acquiredImage!!
       mFileName = imageFileName
   }
    override fun run() {
        val byteBuffer = mImage.planes[0].buffer
            val bytes = ByteArray(byteBuffer.remaining())
            byteBuffer[bytes]
            var fileOutputStream : FileOutputStream
            try {
                fileOutputStream = FileOutputStream(mFileName)
                fileOutputStream.write(bytes)
            } catch (e: Exception) {

            }
    }

}