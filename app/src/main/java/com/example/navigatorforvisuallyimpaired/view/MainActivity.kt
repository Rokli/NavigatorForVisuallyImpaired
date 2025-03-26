package com.example.navigatorforvisuallyimpaired.view

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.example.navigatorforvisuallyimpaired.databinding.MainActivityBinding


class MainActivity : ComponentActivity() {

    private lateinit var binding : MainActivityBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openCameraActivityButton.setOnClickListener { openDetectCameraActivity() }
    }

    private fun openDetectCameraActivity() {
        val intent = Intent(this, CameraActivity::class.java)
        startActivity(intent)
    }
}
