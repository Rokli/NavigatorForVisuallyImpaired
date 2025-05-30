package com.example.navigatorforvisuallyimpaired.service

import com.example.navigatorforvisuallyimpaired.Constants
import com.example.navigatorforvisuallyimpaired.entity.BoundingBox
import com.example.navigatorforvisuallyimpaired.entity.HorizontalDirection
import com.example.navigatorforvisuallyimpaired.entity.LastTimeVoicedObject
import com.example.navigatorforvisuallyimpaired.entity.VerticalDirection
import com.example.navigatorforvisuallyimpaired.entity.VoicedObject

class VoicedObjectServiceImpl : VoicedObjectService {

    private var lastVoicedObjectList: MutableList<LastTimeVoicedObject> = ArrayList()

    override fun getVoicedObject(boundingBoxList: List<BoundingBox>): List<VoicedObject> {
        val voicedObjectList: MutableList<VoicedObject> = ArrayList()
        for (b in boundingBoxList) {
            val voicedObject = LastTimeVoicedObject(
                b.className,
                getHorizontalDirection((b.x1 + b.x2) / 2),
                getVerticalDirection((b.y1 + b.y2) / 2),
                System.currentTimeMillis()
            )
            val lastVoicedObject = lastVoicedObjectList.find {
                it.objectName == voicedObject.objectName
                        && it.verticalDirection == voicedObject.verticalDirection
                        && it.horizontalDirection == voicedObject.horizontalDirection
            }
            if (lastVoicedObject == null) {
                lastVoicedObjectList.add(voicedObject)
                voicedObjectList.add(
                    VoicedObject(
                        voicedObject.objectName,
                        voicedObject.horizontalDirection,
                        voicedObject.verticalDirection
                    )
                )
            } else if (
                voicedObject.lastVoicedTime - lastVoicedObject.lastVoicedTime >= Constants.VOICED_COOLDOWN_MS
            ) {
                lastVoicedObject.lastVoicedTime = voicedObject.lastVoicedTime
                lastVoicedObjectList.add(voicedObject)
                voicedObjectList.add(
                    VoicedObject(
                        voicedObject.objectName,
                        voicedObject.horizontalDirection,
                        voicedObject.verticalDirection
                    )
                )
            } else continue
        }
        return voicedObjectList
    }

    private fun getHorizontalDirection(xm: Float): HorizontalDirection {
        return if (xm <= 0.33) HorizontalDirection.LEFT
        else if (xm >= 0.66) HorizontalDirection.RIGHT
        else HorizontalDirection.MIDDLE
    }

    private fun getVerticalDirection(ym: Float): VerticalDirection {
        return if (ym <= 0.33) VerticalDirection.UP
        else if (ym >= 0.66) VerticalDirection.DOWN
        else VerticalDirection.MIDDLE
    }

}