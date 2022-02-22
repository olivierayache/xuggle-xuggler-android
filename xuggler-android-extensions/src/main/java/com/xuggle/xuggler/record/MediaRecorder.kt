package com.xuggle.xuggler.record

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.xuggle.ferry.IBuffer
import com.xuggle.xuggler.*
import com.xuggle.xuggler.io.XugglerIO
import java.io.File
import java.io.OutputStream
import java.lang.NullPointerException
import java.lang.RuntimeException
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean


data class AudioConfig(
    internal var audioCodec: ICodec.ID = ICodec.ID.AV_CODEC_ID_FLAC,
    internal val sampleRate: Int = 44100,
    internal val bitRate: Int = 192000
)

data class VideoConfig(
    internal val height: Int = 1280,
    internal val width: Int = 720,
    internal var bitRate: Int = 8192000,
    internal val videoCodec: ICodec.ID = ICodec.ID.AV_CODEC_ID_H264
)

/**
 * A media recorder class. This class records audio and video streams. It uses dedicated threads for each stream.
 * To improve performances an input surface is used as input for video encoder (see [MediaCodec.createInputSurface] for more information).
 * The audio stream is composed of the sound captured via [AudioRecord].
 * Never forget to call [release] to properly release recording and encoding material resources.
 *
 * @author Olivier Ayache
 *
 */
class MediaRecorder(
    val audioConfig: AudioConfig = AudioConfig(),
    val videoConfig: VideoConfig = VideoConfig()
) {

    private val finputBuffer: ByteBuffer = ByteBuffer.allocateDirect(2048 + 64)
    private val buffer = IBuffer.make(null, finputBuffer, 0, 2048 + 64)
    private val samples = IAudioSamples.make(buffer, 1, IAudioSamples.Format.FMT_S16)
    private val audioRecord: AudioRecord
    private var started = AtomicBoolean(false)
    private val containerFormat = IContainerFormat.make()
    private val container = IContainer.make()
    private var containerStarted = AtomicBoolean(false)
    private var videoEncoder: IStreamCoder
    private var audioEncoder: IStreamCoder

    @Volatile
    private var csdWritten = false

    @Volatile
    private var headerWritten = false
    private val videoPicture: IVideoPicture
    private var pts: Long = 0
    private var globalPts = -1L
    private var aGlobalPts = -1L
    private var aGlobalDts = -1L
    private var startUTCPts = 0L
    private var startUTCPtsOffset = 0L
    private val readyToWritePacketQueue = ConcurrentLinkedQueue<IPacket>()

    private lateinit var surface: Surface
    private var outputUrl: Uri? = null

    companion object {
        private const val TAG = "MediaRecorder"
        private val EXECUTOR: CompletionService<Void> =
            ExecutorCompletionService(Executors.newSingleThreadExecutor(ThreadFactory { r ->
                Thread(r).apply {
                    name = "recorder-thread"
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                }
            }))

        private lateinit var CONTAINER_THREAD: Thread


        private val CEXECUTOR: CompletionService<Void> =
            ExecutorCompletionService(Executors.newSingleThreadExecutor(ThreadFactory { r ->
                CONTAINER_THREAD = Thread(r).apply {
                    name = "container-thread"
                    Process.setThreadPriority(Process.THREAD_PRIORITY_MORE_FAVORABLE)
                }
                CONTAINER_THREAD
            }))

        private val PACKET_QUEUE = ConcurrentLinkedQueue<IPacket>(List<IPacket>(2000) { IPacket.make() })
    }

    init {

        val minBufferSize = AudioRecord.getMinBufferSize(
            audioConfig.sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        videoPicture = IVideoPicture.make(IPixelFormat.Type.YUV420P, videoConfig.width, videoConfig.height)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, audioConfig.sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBufferSize
        )

        audioEncoder = IStreamCoder.make(IStreamCoder.Direction.ENCODING, audioConfig.audioCodec)

        videoEncoder = IStreamCoder.make(
            IStreamCoder.Direction.ENCODING,
            ICodec.findEncodingCodec(videoConfig.videoCodec, IPixelFormat.Type.MEDIACODEC)
        )

    }

    fun setOutput(outputStream: OutputStream) {
        outputUrl = Uri.parse(XugglerIO.map(outputStream))
    }

    fun setOutput(outputUri: Uri) {
        outputUrl = outputUri
    }

    fun openOutput(
        context: Context,
        onContainerOpened: ((success: Boolean) -> Unit) = {}
    ): Uri {

        var sampleFormat = IAudioSamples.Format.FMT_S16
        var groupOfPictures = 60

        val absolutePath = outputUrl ?: getOutputMediaFile(context)

        outputUrl?.let {
            if (ContentResolver.SCHEME_CONTENT != it.scheme &&
                ContentResolver.SCHEME_FILE != it.scheme &&
                XugglerIO.DEFAULT_PROTOCOL != it.scheme
            ) {
                containerFormat.setOutputFormat("mpegts", null, null)
                audioConfig.audioCodec = ICodec.ID.AV_CODEC_ID_AAC
                sampleFormat = IAudioSamples.Format.FMT_FLTP
                videoConfig.bitRate = 1024000
            } else {
                containerFormat.setOutputFormat("mp4", null, null)
            }
        }

        audioEncoder = IStreamCoder.make(IStreamCoder.Direction.ENCODING, audioConfig.audioCodec)

        videoEncoder = IStreamCoder.make(
            IStreamCoder.Direction.ENCODING,
            ICodec.findEncodingCodec(videoConfig.videoCodec, IPixelFormat.Type.MEDIACODEC)
        )

        CEXECUTOR.submit({

            val options = IMetaData.make().apply {
                //setValue("transtype", "0")
                //setValue("pkt_size", "1316")
            }

            if (container.open(
                    outputUrl.toString(),
                    IContainer.Type.WRITE,
                    containerFormat,
                    false,
                    false,
                    options,
                    null
                ) < 0
            ) {
                onContainerOpened(false)
                return@submit
            }
            container.standardsCompliance = IStreamCoder.CodecStandardsCompliance.COMPLIANCE_EXPERIMENTAL
            container.setProperty("mpegts_copyts", true)
            //container.setProperty("frag_size", 500);
            //container.setProperty("movflags", "frag_keyframe+delay_moov")
            //stream.metaData = IMetaData.make().apply {
            //setValue("rotate", value.toString())
            //}

            container.addNewStream(audioEncoder)
            audioEncoder.sampleFormat = sampleFormat
            audioEncoder.bitRate = audioConfig.bitRate
            audioEncoder.sampleRate = audioRecord.sampleRate
            audioEncoder.timeBase = IRational.make(1, 1000000)
            audioEncoder.channels = audioRecord.channelCount
            audioEncoder.setProperty("ch_mode", "indep")

            container.addNewStream(videoEncoder)
            videoEncoder.frameRate = IRational.make(30, 1)
            videoEncoder.timeBase = IRational.make(1, 1000000)
            videoEncoder.bitRate = videoConfig.bitRate
            videoEncoder.width = videoConfig.width
            videoEncoder.height = videoConfig.height
            videoEncoder.numPicturesInGroupOfPictures = groupOfPictures
            videoEncoder.pixelType = IPixelFormat.Type.MEDIACODEC

            if (audioEncoder.open() < 0 || videoEncoder.open() < 0) {
                onContainerOpened(false)
                return@submit
            }

            surface = videoEncoder.hardwareSurface as Surface

            containerStarted.set(true)

            onContainerOpened(true)

            while (containerStarted.get()) {

                if (csdWritten && !headerWritten) {
                    if (container.writeHeader() < 0) {
                        throw RuntimeException("Container fails to write header")
                    }
                    headerWritten = true
                }

                if (headerWritten) {
                    readyToWritePacketQueue.poll()?.let { packet ->
                        container.writePacket(packet) // TODO: throws error on negative result
                        PACKET_QUEUE.offer(packet)
                    }
                }

            }

            if (!Thread.currentThread().isInterrupted && container.isHeaderWritten) {
                container.writeTrailer()
            }

            PACKET_QUEUE.addAll(readyToWritePacketQueue)
            readyToWritePacketQueue.clear()

        }, null)

        return absolutePath
    }

    /**
     * Resets the global state of the media recorder
     */
    private fun reset() {
        globalPts = -1L
        aGlobalPts = -1L
        aGlobalDts = -1L
        pts = 0
        csdWritten = false
        headerWritten = false
    }

    /**
     * Starts the audio recording.
     */
    fun startAudioRecording() {
        if (started.get()) {
            return
        }
        audioRecord.startRecording()
    }

    /**
     * Starts the media recorder.
     * This call starts the audio and video workers in dedicated threads using [ExecutorCompletionService]
     */
    fun start() {
        if (started.getAndSet(true)) {
            return
        }

        EXECUTOR.submit({
            try {

                while (started.get()) {
                    processVideo()
                    processAudio()
                }
                audioRecord.stop()
                var read = processAudio()
                while (read > 0) {
                    read = processAudio()
                }
                flushEncoder(audioEncoder)
                flushEncoder(videoEncoder)
                containerStarted.set(false)
                surface.release()
                reset()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }, null)
    }

    fun cancelConnection() {
        CONTAINER_THREAD.interrupt()
    }

    /**
     * Stops audio and video recording, mixing and encoding
     */
    fun stop() {
        if (started.compareAndSet(true, false)) {
            EXECUTOR.take()
            CEXECUTOR.poll(200, TimeUnit.MILLISECONDS)
            cancelConnection()
            audioEncoder.close()
            videoEncoder.close()
            container.close(true)
        }
    }

    /**
     * Releases all recording en encoding resources. After this call the media recorder must not be used again
     */
    fun release() {
        audioRecord.release()
        container.delete()
        audioEncoder.delete()
        videoEncoder.delete()
    }

    /**
     * Returns the [Surface] created by the video encoder.
     * This surface has been created by [MediaCodec.createInputSurface].
     * It will be invalid after call to stop.
     * To get a new valid surface again a fresh call to [reset] must be done.
     *
     * @return the [MediaCodec] input surface to encode video
     */
    fun getInputSurface(): Surface {
        return surface
    }

    /**
     * Process recorded audio and encode it.
     */
    private fun processAudio(): Int {

        var read = -1

        finputBuffer.rewind()
        read = audioRecord.read(finputBuffer, 2048)

        if (read > 0) {
            samples.setComplete(
                true,
                read / (2 * audioRecord.channelCount),
                audioRecord.sampleRate,
                audioRecord.channelCount,
                IAudioSamples.Format.FMT_S16,
                pts
            )
            pts += (read * 1000000L / (2 * audioRecord.channelCount)) / audioRecord.sampleRate
            val samplesToEncode = samples

            if (samplesToEncode.isComplete) {

                val remaining = samplesToEncode.numSamples
                var consumed = 0

                while (consumed < remaining) {
                    val audioPacket = PACKET_QUEUE.poll() ?: let {
                        Log.w(TAG, "Packet buffer empty, data will be lost")
                        readyToWritePacketQueue.poll()
                    }
                    consumed += audioEncoder.encodeAudio(audioPacket, samplesToEncode, consumed)
                    if (audioPacket.isComplete) {
                        if (aGlobalPts == -1L && audioPacket.pts >= 0) {
                            aGlobalPts = audioPacket.pts
                            aGlobalDts = audioPacket.dts
                            Log.w(
                                TAG,
                                "Audio GlobalPts set to $aGlobalPts"
                            )
                        }
                        audioPacket.pts = audioPacket.pts - aGlobalPts + startUTCPts
                        audioPacket.dts = audioPacket.dts - aGlobalDts + startUTCPts
                        readyToWritePacketQueue.offer(audioPacket)
                    } else {
                        PACKET_QUEUE.offer(audioPacket)
                    }
                }
            }
        }

        return read

    }

    /**
     * Process video captured by the camera via a [Surface] and encode it.
     */
    private fun processVideo() {

        val videoPacket = PACKET_QUEUE.poll() ?: let {
            Log.w(TAG, "Packet buffer empty, data will be lost")
            readyToWritePacketQueue.poll()
        }
        videoEncoder.encodeVideo(videoPacket, null, videoPicture.size)
        if (!csdWritten && videoPacket.isComplete) {
            videoPacket.pts = 0
            videoPacket.dts = videoPacket.pts
            videoEncoder.setExtraData(videoPacket.data, 0, videoPacket.size, true)
            csdWritten = true
            PACKET_QUEUE.offer(videoPacket)
        } else if (videoPacket.isComplete) {
            if (globalPts == -1L && videoPacket.pts > 0) {
                globalPts = videoPacket.pts
                Log.w(TAG, "GlobalPts setted to " + globalPts)
                if (startUTCPts == 0L && "mpegts" == containerFormat.outputFormatShortName) {
                    //startUTCPts = videoPacket.timeBase.rescale(System.currentTimeMillis() - startUTCPtsOffset, IRational.make(1, 1000))
                }
            }

            videoPacket.pts = videoPacket.pts - globalPts + startUTCPts
            videoPacket.dts = videoPacket.pts
            readyToWritePacketQueue.offer(videoPacket)
        } else {
            PACKET_QUEUE.offer(videoPacket)
        }
    }

    /**
     * Flush an encoder.
     * Asks the encoder to encode the last audio frame/pictures in their buffers and encode them.
     *
     * @param encoder the encoder to flush
     */
    private fun flushEncoder(encoder: IStreamCoder) {

        Log.w(TAG, "Flusing encoder $encoder")

        if (encoder.codecType == ICodec.Type.CODEC_TYPE_AUDIO) {
            val audioPacket = PACKET_QUEUE.poll() ?: let {
                Log.w(TAG, "Packet buffer empty, data will be lost")
                readyToWritePacketQueue.poll()
            }
            audioEncoder.encodeAudio(audioPacket, null, 0)
            if (audioPacket.isComplete) {
                audioPacket.pts = audioPacket.pts - aGlobalPts + startUTCPts
                audioPacket.dts = audioPacket.dts - aGlobalDts + startUTCPts
                readyToWritePacketQueue.offer(audioPacket)
                flushEncoder(audioEncoder)
            }
        } else {
            val videoPacket = PACKET_QUEUE.poll() ?: let {
                Log.w(TAG, "Packet buffer empty, data will be lost")
                readyToWritePacketQueue.poll()
            }
            videoEncoder.encodeVideo(videoPacket, null, videoPicture.size)
            if (videoPacket.isComplete) {
                videoPacket.pts = videoPacket.pts - globalPts + startUTCPts
                videoPacket.dts = videoPacket.pts
                readyToWritePacketQueue.offer(videoPacket)
                flushEncoder(videoEncoder)
            }
        }

    }


    /** Create a File for saving an image or video */
    private fun getOutputMediaFile(context: Context): Uri {

        val uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        // Create a media file name
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

        // Create a media file name
        val cv = ContentValues().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.VideoColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/mix-recorder")
            } else {
                val mediaStorageDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "mix-recorder"
                )
                // This location works best if you want the created images to be shared
                // between applications and persist after your app has been uninstalled.

                // Create the storage directory if it does not exist
                mediaStorageDir.apply {
                    if (!exists()) {
                        if (!mkdirs()) {
                            Log.d("mix-recorder", "failed to create directory")
                            throw NullPointerException("failed to create directory")
                        }
                    }
                }
                put(MediaStore.Video.VideoColumns.DATA, "${mediaStorageDir.path}${File.separator}VID_$timeStamp.mp4")
            }
            put(MediaStore.Video.VideoColumns.MIME_TYPE, "video/avc")
            put(MediaStore.Video.VideoColumns.DISPLAY_NAME, "VID_$timeStamp.mp4")
        }
        return Objects.requireNonNull(context.contentResolver.insert(uri, cv))!!

    }
}