package com.romrenamer.app.core.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomFileFilterTest {

    @Test
    fun `accepts known ROM extensions and ignores everything else`() {
        val filter = RomFileFilter()

        assertTrue(filter.accepts("Super Mario World (USA).sfc", 524288))
        assertTrue(filter.accepts("Sonic.ZIP", 1024))
        assertFalse(filter.accepts("boxart.png", 1024))
        assertFalse(filter.accepts("gamelist.txt", 1024))
    }

    @Test
    fun `a null extension set accepts every file`() {
        val filter = RomFileFilter(extensions = null)

        assertTrue(filter.accepts("mystery-dump", 1024))
        assertTrue(filter.accepts("notes.txt", 1024))
    }

    @Test
    fun `skips hidden files unless asked for them`() {
        assertFalse(RomFileFilter().accepts(".hidden.sfc", 1024))
        assertTrue(RomFileFilter(includeHidden = true).accepts(".hidden.sfc", 1024))
    }

    @Test
    fun `skips empty files, which can never match a DAT entry`() {
        assertFalse(RomFileFilter().accepts("Empty.sfc", 0))
    }

    @Test
    fun `honours the size window`() {
        val filter = RomFileFilter(minSizeBytes = 1024, maxSizeBytes = 4096)

        assertFalse(filter.accepts("Tiny.sfc", 512))
        assertTrue(filter.accepts("Right.sfc", 2048))
        assertFalse(filter.accepts("Huge.sfc", 8192))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        val filter = RomFileFilter(extensions = setOf("sfc"))

        assertTrue(filter.accepts("Game.SFC", 1024))
        assertTrue(filter.accepts("Game.sFc", 1024))
    }
}
