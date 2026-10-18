package com.stronghold.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DataUrlBytesTest {
    @Test
    fun decodesBase64Image() {
        val url = "data:image/png;base64,iVBORw0KGgo="
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), dataUrlBytes(url))
    }

    @Test
    fun decodesPercentEncodedCssWithoutChangingPlus() {
        val url = "data:text/css;charset=utf-8,%40font-face%20%7B%20font-family%3A%20A%2BB%3B%20%7D"
        assertArrayEquals("@font-face { font-family: A+B; }".toByteArray(), dataUrlBytes(url))
    }

    @Test
    fun rejectsMalformedAndNetworkUrls() {
        assertNull(dataUrlBytes("data:image/png;base64,%%%"))
        assertNull(dataUrlBytes("data:image/png;base64"))
        assertNull(dataUrlBytes("https://example.com/image.png"))
    }
}
