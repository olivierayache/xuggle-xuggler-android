package com.xuggle.xuggler.record

import android.content.Context
import android.util.AttributeSet
import android.widget.CompoundButton

class SwitchImageButton : CompoundButton {
    constructor(context: Context?, attrs: AttributeSet?) : super(context, attrs) {
        refreshDrawableState()
        isChecked = false
    }
}