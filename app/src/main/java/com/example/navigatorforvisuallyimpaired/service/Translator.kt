package com.example.navigatorforvisuallyimpaired.service

interface Translator {
    var locale: String
    fun getLocaleString(key:String):String
}