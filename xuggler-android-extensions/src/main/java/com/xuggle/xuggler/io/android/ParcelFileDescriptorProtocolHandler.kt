package com.xuggle.xuggler.io.android

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import com.xuggle.xuggler.io.IURLProtocolHandler
import org.slf4j.LoggerFactory
import java.io.FileDescriptor
import java.io.FileNotFoundException

class ParcelFileDescriptorProtocolHandler(private val context: Context) : IURLProtocolHandler{

    private var pfd: ParcelFileDescriptor? = null
    private lateinit var fd: FileDescriptor

    companion object {
        private val LOG = LoggerFactory.getLogger(ParcelFileDescriptorProtocolHandler::class.java)
    }


    override fun open(url: String, flags: Int): Int {
        val mode = when (flags) {
            IURLProtocolHandler.URL_RDONLY_MODE ->  "r"
            IURLProtocolHandler.URL_RDWR ->  "rw"
            IURLProtocolHandler.URL_WRONLY_MODE ->  "w"
            else -> {
                LOG.error("Invalid flag passed to open: {}", flags)
                return -1
            }
        }
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(url), mode)?.let {
                pfd = it
                fd = it.fileDescriptor
            }
        }catch (ex: FileNotFoundException){
            LOG.error(ex.toString())
            return -1
        }
        return 0
    }

    override fun write(data: ByteArray, size: Int): Int {
        return Os.write(fd, data, 0, size)
    }

    override fun isStreamed(url: String, flags: Int): Boolean {
        return true
    }

    override fun seek(offset: Long, whence: Int): Long {
        return Os.lseek(fd, offset, whence)
    }

    override fun close(): Int {
        pfd?.close()
        return 0
    }

    override fun read(data: ByteArray, size: Int): Int {
        return Os.read(fd, data, 0, size)
    }
}