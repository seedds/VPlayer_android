package com.seedds.vplayer.player

import org.junit.Assert.assertEquals
import org.junit.Test

class ResumePolicyTest {

    @Test
    fun `a fresh video starts at zero`() {
        assertEquals(0.0, ResumePolicy.resumePosition(0.0, 100.0), 0.0)
        assertEquals(0.0, ResumePolicy.resumePosition(-4.0, 100.0), 0.0)
        assertEquals(0.0, ResumePolicy.resumePosition(Double.NaN, 100.0), 0.0)
    }

    @Test
    fun `an unknown duration resumes exactly where it was left`() {
        assertEquals(42.0, ResumePolicy.resumePosition(42.0, 0.0), 0.0)
        assertEquals(42.0, ResumePolicy.resumePosition(42.0, Double.NaN), 0.0)
    }

    @Test
    fun `a position with room left is used as is`() {
        assertEquals(42.0, ResumePolicy.resumePosition(42.0, 100.0), 0.0)
        assertEquals(90.0, ResumePolicy.resumePosition(90.0, 100.0), 0.0)
    }

    @Test
    fun `a position near the end rewinds so the ending replays`() {
        assertEquals(90.0, ResumePolicy.resumePosition(95.0, 100.0), 0.0)
        assertEquals(90.0, ResumePolicy.resumePosition(100.0, 100.0), 0.0)
        // A position past the end is clamped first, then rewound.
        assertEquals(90.0, ResumePolicy.resumePosition(500.0, 100.0), 0.0)
    }

    @Test
    fun `a video shorter than the rewind window restarts`() {
        assertEquals(0.0, ResumePolicy.resumePosition(4.0, 5.0), 0.0)
    }
}
