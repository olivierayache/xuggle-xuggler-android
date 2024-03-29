package com.xuggle

import android.content.ContentProvider
import android.content.ContentResolver
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Build
import androidx.core.content.ContentProviderCompat
import com.xuggle.ferry.ITempFileCreator
import com.xuggle.xuggler.io.URLProtocolManager
import com.xuggle.xuggler.io.android.ParcelFileDescriptorProtocolHandler
import java.io.File
import java.lang.Exception

class XugglerProvider: ContentProvider() {

    override fun onCreate(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                ITempFileCreator.Builder.setDefault { s, _, _ -> File(context?.filesDir, s).apply { deleteOnExit() } }
            }
            URLProtocolManager.getManager().registerFactory(
                ContentResolver.SCHEME_CONTENT
            ) { _: String, _: String, _: Int ->
                ParcelFileDescriptorProtocolHandler(
                    ContentProviderCompat.requireContext(this)
                )
            }
            true
        }catch (ex : Exception){
            false
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        return null
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return null
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int {
        return 0
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        return 0
    }

    override fun getType(uri: Uri): String? {
        return null
    }

}