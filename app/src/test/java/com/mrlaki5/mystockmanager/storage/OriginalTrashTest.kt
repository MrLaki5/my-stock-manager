package com.mrlaki5.mystockmanager.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OriginalTrashTest {

    private fun idOf(authority: String, path: String) = OriginalTrash.localMediaId(authority, path.split('/'))

    @Test
    fun `a local photo picker pick resolves to its MediaStore id`() {
        assertEquals(1000037445L, idOf("media", "picker/0/com.android.providers.media.photopicker/media/1000037445"))
        assertEquals(42L, idOf("media", "picker_get_content/0/com.android.providers.media.photopicker/media/42"))
    }

    @Test
    fun `a cloud photo picker pick has no local original`() {
        assertNull(idOf("media", "picker/0/com.google.android.apps.photos.cloudpicker/media/123"))
    }

    @Test
    fun `a plain MediaStore image or video uri is used as is`() {
        assertEquals(7L, idOf("media", "external/images/media/7"))
        assertEquals(7L, idOf("media", "external_primary/images/media/7"))
        assertEquals(8L, idOf("media", "external/video/media/8"))
    }

    @Test
    fun `other providers and media types are skipped`() {
        assertNull(idOf("media", "external/audio/media/7"))
        assertNull(idOf("com.whatsapp.provider.media", "item/7"))
    }
}
