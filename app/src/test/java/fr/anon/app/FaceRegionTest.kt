package fr.anon.app

import org.junit.Assert.*
import org.junit.Test

class FaceRegionTest {
    @Test fun paddingClampsToImageAndNeverShrinksFace() {
        val face = FaceRegion(1, .01f, .02f, .99f, .98f)
        assertEquals(FaceRegion(1, 0f, 0f, 1f, 1f), face.padded())
    }
    @Test fun movementMaskCoversBothPositions() {
        val previous = FaceRegion(3, .2f, .3f, .4f, .6f)
        val current = FaceRegion(3, .35f, .4f, .55f, .7f)
        val mask = current.union(previous)
        assertTrue(mask.contains(.2f, .3f))
        assertTrue(mask.contains(.55f, .7f))
        assertEquals(3, mask.id)
    }
}
