package com.example.navigatorforvisuallyimpaired.service

import android.content.Context
import com.example.navigatorforvisuallyimpaired.Constants

class LocaleFileTranslator(
    private val context: Context,
):Translator {
    private val translations: MutableMap<String, String> = mutableMapOf()
    init {
        loadTranslations()
    }
    override var locale: String = "en"
        set(value) {
            field = value
            loadTranslations()
        }

    override fun getLocaleString(key:String):String{
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