package com.example.navigatorforvisuallyimpaired.service

import com.example.navigatorforvisuallyimpaired.entity.BoundingBox
import com.example.navigatorforvisuallyimpaired.entity.VoicedObject

interface VoicedObjectService {
    fun getVoicedObject(boundingBoxList: List<BoundingBox>): List<VoicedObject>
}