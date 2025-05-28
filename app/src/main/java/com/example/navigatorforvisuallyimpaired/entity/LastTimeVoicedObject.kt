package com.example.navigatorforvisuallyimpaired.entity

class LastTimeVoicedObject(
    var objectName: String,
    var horizontalDirection: HorizontalDirection,
    var verticalDirection: VerticalDirection,
    var lastVoicedTime : Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if(other == null || other !is LastTimeVoicedObject) return false
        val o2 = other as LastTimeVoicedObject
        return objectName == o2.objectName
                && horizontalDirection == o2.horizontalDirection
                && verticalDirection == o2.verticalDirection
    }

    override fun hashCode(): Int {
        return (objectName+horizontalDirection.name+verticalDirection.name).hashCode()
    }
}
