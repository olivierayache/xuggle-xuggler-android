package com.xuggle.xuggler.record

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.os.Looper
import android.renderscript.*
import android.util.Log
import android.view.Surface
import com.xuggle.ScriptC_rotate

open class RecordingSessionManager(
    private val context: Context,
    private val mr: MediaRecorder
) {

    private lateinit var inputAllocation: Allocation
    private lateinit var outputAllocation: Allocation
    private lateinit var rotate: ScriptC_rotate
    private var previewSurface: Surface? = null
    private lateinit var camera: CameraDevice
    private val cm: CameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private var previewSession: CameraCaptureSession? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraDirection = CameraCharacteristics.LENS_FACING_FRONT
    private var flashEnabled = false
    private val flashAvailable: Map<String, Boolean> by lazy {
        val map = HashMap<String, Boolean>()
        for (id in cm.cameraIdList) {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE)?.let {
                map.put(id, it)
            }
        }
        map
    }
    private val sensorOrientation: Map<String, Int> by lazy {
        val map = HashMap<String, Int>()
        for (id in cm.cameraIdList) {
            cm.getCameraCharacteristics(id).get(CameraCharacteristics.SENSOR_ORIENTATION)?.let {
                map.put(id, it)
            }
        }
        map
    }
    private val controller = Controller()
    private var applyRotation: ((Allocation) -> Unit) = {}
    private var state: State = State.IDLE

    enum class State {
        IDLE,
        PREPARING,
        PREPARED,
        SWITCH,
        SWITCH_RECORDING,
        RECORDING
    }

    open fun onPreviewSessionConfigured(flashSupported: Boolean) {}

    open fun onCaptureSessionConfigured() {}

    /**
     * Opens the camera device defined by the [cameraDirection].
     * A preview should start when the camera is opened (on [CameraDevice.StateCallback.onOpened] callback called)
     */
    @SuppressLint("MissingPermission")
    fun openCamera(onOpened: Controller.() -> Unit = {}) {
        val cameraId = cm.cameraIdList.find {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == cameraDirection
        }
        if (cameraId != null) {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    this@RecordingSessionManager.camera = camera
                    onOpened(controller)
                    if (state == State.SWITCH_RECORDING) {
                        startRecordSession(
                            onStopRecording = {
                                mr.stop()
                                state = State.IDLE
                                previewSurface?.let(this@RecordingSessionManager::preparePreview)
                            }
                        )
                    } else {
                        previewSurface?.let(this@RecordingSessionManager::preparePreview)
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                }

                override fun onClosed(camera: CameraDevice) {
                    when (state) {
                        State.SWITCH_RECORDING -> openCamera()
                        State.SWITCH -> {
                            state = State.IDLE
                            openCamera()
                        }
                        State.RECORDING -> state = State.IDLE
                    }
                }

            }, null)
        }
    }

    fun close() {
        mr.stop()
        camera.close()
        previewSurface = null
    }

    /**
     * Prepares and start camera preview. The preview output is generally sent
     * to the surface hold a [android.view.SurfaceView]
     *
     * @param surface the preview surface
     */
    private fun preparePreview(surface: Surface) {
        val previewRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewRequest.set(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        )

        if (flashEnabled) {
            previewRequest.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_TORCH
            )
        }

        previewRequest.addTarget(surface)
        camera.createCaptureSession(
            listOf(surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    onPreviewSessionConfigured(flashAvailable[session.device.id] == true)
                    session.setRepeatingRequest(
                        previewRequest.build(),
                        object : CameraCaptureSession.CaptureCallback() {
                            override fun onCaptureSequenceCompleted(
                                session: CameraCaptureSession,
                                sequenceId: Int,
                                frameNumber: Long
                            ) {
                                if (state == State.SWITCH) {
                                    session.device.close()
                                }
                            }

                            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                                if (state == State.SWITCH) {
                                    session.device.close()
                                }
                            }
                        },
                        null
                    )
                    previewSession = session
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                }
            },
            null
        )
    }

    /**
     * Start a new record session [android.hardware.camera2.CameraCaptureSession].
     * Recording and playing will be start on the first call to [CameraCaptureSession.CaptureCallback.onCaptureStarted]
     * @param onStartRecording function called when first capture starts
     * @param onStopRecording function called when last capture ends [CameraCaptureSession.StateCallback.onReady]
     */
    private inline fun startRecordSession(
        crossinline onStartRecording: () -> Unit = {},
        crossinline onStopRecording: () -> Unit = {}
    ) {

        var createCaptureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        createCaptureRequest.set(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        )

        if (flashEnabled) {
            createCaptureRequest.set(
                CaptureRequest.FLASH_MODE,
                CaptureRequest.FLASH_MODE_TORCH
            )
        }

        createCaptureRequest.addTarget(inputAllocation.surface)
        previewSurface?.let(createCaptureRequest::addTarget)
        val surfaceList = mutableListOf<Surface>(inputAllocation.surface)
        previewSurface?.let(surfaceList::add)
        camera.createCaptureSession(
            surfaceList,
            object : CameraCaptureSession.StateCallback() {

                override fun onClosed(session: CameraCaptureSession) {
                    Log.w("ACT", "onClosed $session $state")
                    if (state == State.SWITCH_RECORDING) {
                        session.device.close()
                    }
                }

                override fun onConfigured(session: CameraCaptureSession) {
                    onCaptureSessionConfigured()
                    captureSession = session
                    applyRotation =
                        when (sensorOrientation[camera.id]) {
                            90 -> rotate::forEach_rotate90
                            180 -> rotate::forEach_rotate180
                            270 -> rotate::forEach_rotate270
                            else -> throw IllegalArgumentException("Sensor orientation is invalid")
                        }

                    if (state == State.SWITCH_RECORDING) {
                        state = State.RECORDING
                    }

                    session.setRepeatingRequest(
                        createCaptureRequest.build(),
                        object : CameraCaptureSession.CaptureCallback() {

                            override fun onCaptureStarted(
                                session: CameraCaptureSession,
                                request: CaptureRequest,
                                timestamp: Long,
                                frameNumber: Long
                            ) {
                                if (state == State.PREPARED) {
                                    onStartRecording()
                                }
                            }

                            override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
                                if (state == State.RECORDING) {
                                    onStopRecording()
                                }

                                if (state == State.SWITCH_RECORDING) {
                                    session.close()
                                }

                            }

                            override fun onCaptureSequenceCompleted(
                                session: CameraCaptureSession,
                                sequenceId: Int,
                                frameNumber: Long
                            ) {
                                Log.w(
                                    "ACT",
                                    "onCaptureSequenceCompleted $sequenceId $session ${Thread.currentThread()}"
                                )
                                if (state == State.RECORDING) {
                                    onStopRecording()
                                }

                                if (state == State.SWITCH_RECORDING) {
                                    session.close()
                                }
                            }

                        },
                        null
                    )
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                }
            },
            null
        )
    }

    inner class Controller {

        fun enablePreview(surface: Surface) {
            val height = mr.videoConfig.width
            val width = mr.videoConfig.height
            previewSurface = surface

            //TODO: move this code
            val create = RenderScript.create(context)

            inputAllocation = Allocation.createTyped(
                create,
                Type.Builder(create, Element.U8_4(create)).setX(width).setY(height)
                    .setYuvFormat(ImageFormat.YUV_420_888).create(),
                Allocation.USAGE_SCRIPT or Allocation.USAGE_IO_INPUT
            )

            outputAllocation = Allocation.createTyped(
                create,
                Type.Builder(create, Element.RGBA_8888(create)).setX(height).setY(width).create(),
                Allocation.USAGE_SCRIPT or Allocation.USAGE_IO_OUTPUT
            )

            val inputAllocationRGB = Allocation.createTyped(
                create,
                Type.Builder(create, Element.U8_4(create)).setX(width).setY(height).create(),
                Allocation.USAGE_SCRIPT
            )

            rotate = ScriptC_rotate(create)
            rotate._width = width
            rotate._height = height
            rotate._output_allocation = outputAllocation
            val yuvToRGB = ScriptIntrinsicYuvToRGB.create(create, Element.U8_4(create))

            inputAllocation.setOnBufferAvailableListener { inputAllocation ->
                run {
                    inputAllocation.ioReceive()
                    yuvToRGB.setInput(inputAllocation)
                    yuvToRGB.forEach(inputAllocationRGB)
                    applyRotation(inputAllocationRGB)
                    outputAllocation.ioSend()
                }
            }

        }

        /**
         * Switch the camera direction
         */
        fun switchCamera() {
            when (state) {
                State.IDLE,
                State.RECORDING -> {
                    cameraDirection = when (cameraDirection) {
                        CameraCharacteristics.LENS_FACING_FRONT -> CameraCharacteristics.LENS_FACING_BACK
                        CameraCharacteristics.LENS_FACING_BACK -> CameraCharacteristics.LENS_FACING_FRONT
                        else -> throw IllegalStateException("This camera direction is not allowed")
                    }
                    state = if (state == State.RECORDING) State.SWITCH_RECORDING else State.SWITCH

                    when (state) {
                        State.SWITCH -> previewSession?.stopRepeating()
                        State.SWITCH_RECORDING -> captureSession?.stopRepeating()
                    }
                }
            }
        }

        /**
         * Turns the flash on/off.
         * Switching the flash state will restart preview.
         * Switching the flash state is only enabled during preview
         *@param enable the requested state for the flash
         */
        fun switchFlash(enable: Boolean) {
            flashEnabled = enable
            previewSurface?.let(this@RecordingSessionManager::preparePreview)
        }

        /**
         * Start/Stop recording
         */
        fun record() {
            when (state) {
                State.IDLE -> {
                    state = State.PREPARING
                    mr.openOutput(context) { success ->
                        if (success) {
                            (context as Activity).runOnUiThread {
                                outputAllocation.surface = mr.getInputSurface()
                                state = State.PREPARED
                                startRecordSession(
                                    onStartRecording = {
                                        mr.startAudioRecording()
                                        mr.start()
                                        state = State.RECORDING
                                    },
                                    onStopRecording = {
                                        mr.stop()
                                        state = State.IDLE
                                        previewSurface?.let(this@RecordingSessionManager::preparePreview)
                                    }
                                )
                            }
                        }
                    }
                }
                State.RECORDING -> {
                    captureSession?.stopRepeating()
                }
            }
        }
    }
}