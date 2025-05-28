package com.example.navigatorforvisuallyimpaired.service

interface DepthCameraImageListener {
    fun onNewImage(depthMap: ShortArray)
}