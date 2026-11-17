package io.github.sodarx.biliplay.core

import org.junit.Assert.*
import org.junit.Test

class StorageSizeTest {
    @Test
    fun zeroAndNegativeSizesStayEmpty() {
        assertEquals("0 B", StorageSize.format(0))
        assertEquals("0 B", StorageSize.format(-1))
    }

    @Test
    fun bytesBelowOneKilobyteUsePlainBytes() {
        assertEquals("512 B", StorageSize.format(512))
        assertEquals("1023 B", StorageSize.format(1023))
    }

    @Test
    fun unitBoundariesSwitchAtPowersOfTwo() {
        assertEquals("1.0 KB", StorageSize.format(1024))
        assertEquals("1.5 KB", StorageSize.format(1536))
        assertEquals("1.0 MB", StorageSize.format(1024L * 1024))
        assertEquals("1.0 GB", StorageSize.format(1024L * 1024 * 1024))
        assertEquals("1.0 TB", StorageSize.format(1024L * 1024 * 1024 * 1024))
    }

    @Test
    fun roundingKeepsShortValuesAndCapsHugeValues() {
        assertEquals("12.3 MB", StorageSize.format((12.34 * 1024 * 1024).toLong()))
        assertEquals("999 KB", StorageSize.format((999.4 * 1024).toLong()))
        assertEquals("2.5 MB", StorageSize.format((2.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun enormousValuesStayBoundedToTheLargestUnit() {
        val text = StorageSize.format(Long.MAX_VALUE)
        assertTrue(text.endsWith("TB"))
    }
}
