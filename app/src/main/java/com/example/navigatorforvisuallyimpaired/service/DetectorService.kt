package com.example.navigatorforvisuallyimpaired.service

import android.graphics.Bitmap

interface DetectorService {
    fun detect(frame: Bitmap, depthImage: ShortArray)
    fun restart(isGpu: Boolean)
    fun close()
}