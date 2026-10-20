package nl.giejay.android.tv.immich.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class GuidedFieldValueTest {
    @Test fun `uncommitted editor text takes precedence`() {
        assertEquals("PUBLIC-new-key", guidedFieldValue("PUBLIC-new-key", "PUBLIC-old-key"))
    }
    @Test fun `recycled row uses committed value instead of literal null`() {
        assertEquals("PUBLIC-key", guidedFieldValue(null, "PUBLIC-key"))
    }
    @Test fun `clearing a visible field does not restore the old key`() {
        assertEquals("", guidedFieldValue("", "PUBLIC-old-key"))
    }
    @Test fun `missing values are empty instead of literal null`() {
        assertEquals("", guidedFieldValue(null, null))
    }
}
