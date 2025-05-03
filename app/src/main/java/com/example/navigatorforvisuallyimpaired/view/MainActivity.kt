package com.example.navigatorforvisuallyimpaired.view

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.navigatorforvisuallyimpaired.databinding.MainActivityBinding


class MainActivity : ComponentActivity() {

    private lateinit var binding : MainActivityBinding
    private var exitFlag : Boolean = false;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openCameraActivityButton.setOnClickListener { openDetectCameraActivity() }
    }

    private fun openDetectCameraActivity() {
        if(allPermissionsGranted()) {
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
            finish()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

    }

    @Override
    public override fun onBackPressed() {
        if (!exitFlag){
            Toast.makeText(this.baseContext,  "Нажмите еще раз для выхода", Toast.LENGTH_SHORT).show()
            exitFlag = true
            super.onBackPressed()
        } else {
            finish()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val TAG = "Camera"
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = mutableListOf (
            Manifest.permission.CAMERA
        ).toTypedArray()
    }
}
