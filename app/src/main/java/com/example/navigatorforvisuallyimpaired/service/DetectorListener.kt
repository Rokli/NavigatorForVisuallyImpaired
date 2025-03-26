package com.example.navigatorforvisuallyimpaired.service

import com.example.navigatorforvisuallyimpaired.entity.BoundingBox

interface DetectorListener {
    fun onEmptyDetect()
    fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long)
}