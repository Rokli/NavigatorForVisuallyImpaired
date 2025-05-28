package com.example.navigatorforvisuallyimpaired.service

import android.content.Context
import com.example.navigatorforvisuallyimpaired.Constants

class Translator(
    private val context: Context,
) {
    private val translations: MutableMap<String, String> = mutableMapOf()
    init {
        loadTranslations()
    }
    var locale: String = "en"
        set(value) {
            field = value
            loadTranslations()
        }

    fun getLocaleString(key:String):String{
        return translations[key].orEmpty()
    }

    private fun loadTranslations() {
        val fileName = Constants.LOCALE_FILE_NAME + locale + Constants.LOCALE_FILE_TYPE
        try {
            context.assets.open(fileName).bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val trimmedLine = line.trim()
                    if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                        return@forEach
                    }
                    val parts = trimmedLine.split("=", limit = 2)
                    if (parts.size == 2) {
                        val key = parts[0].trim()
                        val value = parts[1].trim()
                        translations[key] = value
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}