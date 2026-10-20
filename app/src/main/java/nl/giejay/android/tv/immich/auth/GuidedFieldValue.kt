package nl.giejay.android.tv.immich.auth

/** Read in-progress edits; recycled/off-screen rows fall back to the committed action model. */
internal fun guidedFieldValue(editorText: CharSequence?, committedText: CharSequence?): String =
    editorText?.toString() ?: committedText?.toString().orEmpty()
