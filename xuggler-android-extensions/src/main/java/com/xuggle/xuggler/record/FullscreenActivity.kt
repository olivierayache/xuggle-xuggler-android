package com.xuggle.xuggler.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.xuggle.R
import kotlinx.android.synthetic.main.activity_record.*

open class FullscreenActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private lateinit var recordButton: SwitchImageButton
    private lateinit var flash: SwitchImageButton
    private lateinit var switch: SwitchImageButton
    private val recordingSessionManager: RecordingSessionManager by lazy {
        object : RecordingSessionManager(this, createMediaRecorder()) {

            override fun onCaptureSessionConfigured() {
                flash.visibility = View.INVISIBLE
            }

            override fun onPreviewSessionConfigured(flashSupported: Boolean) {
                flash.visibility = if (flashSupported) View.VISIBLE else View.INVISIBLE
            }
        }
    }

    /**
     * Set full screen for this activity
     */
    private fun enableFullScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LOW_PROFILE or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        enableFullScreen()
    }

    override fun onResume() {
        super.onResume()
        enableFullScreen()
    }

    /**
     * Checks that permissions passed in param are granted
     * @param permissions an array of permissions to check
     * @return true if the permissions passed in param are granted
     */
    private fun checkForPermissions(vararg permissions: String): Boolean {
        var ret = true
        for (permission in permissions) {
            ret = ret && ActivityCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ret
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 0 && permissions.size == grantResults.count { i -> i == PackageManager.PERMISSION_GRANTED }) {
            setContentView()
        }
    }

    private fun initButtons(layout: View) {
        recordButton = layout.findViewById(R.id.record)
        flash = layout.findViewById(R.id.flash)
        switch = layout.findViewById(R.id.flip)
    }

    private fun setContentView() {
        setContentView(R.layout.activity_record)
        vertical_actions.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0) {
                horizontal_actions.visibility = View.GONE
                initButtons(vertical_actions)
            } else {
                vertical_actions.visibility = View.GONE
                initButtons(horizontal_actions)
            }
        }
        camera_prev.holder.addCallback(this@FullscreenActivity)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!checkForPermissions(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                0
            )
        } else {
            setContentView()
        }
    }

    open fun createMediaRecorder(): MediaRecorder = MediaRecorder()

    override fun surfaceCreated(holder: SurfaceHolder) {
        recordingSessionManager.openCamera {
            enablePreview(holder)
            recordButton.visibility = View.VISIBLE
            switch.visibility = View.VISIBLE
            flash.visibility = View.VISIBLE
            recordButton.setOnCheckedChangeListener { _, _ -> record() }
            switch.setOnCheckedChangeListener { _, _ -> switchCamera() }
            flash.setOnCheckedChangeListener { _, isChecked -> switchFlash(isChecked) }
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        recordingSessionManager.close()
    }

}