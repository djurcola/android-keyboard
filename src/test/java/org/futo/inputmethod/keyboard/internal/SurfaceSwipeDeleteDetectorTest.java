/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.futo.inputmethod.keyboard.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class SurfaceSwipeDeleteDetectorTest {
    private static final int STEP = 100;
    private static final int START_X = 1000;

    private SurfaceSwipeDeleteDetector mDetector;

    @Before
    public void setUp() {
        mDetector = new SurfaceSwipeDeleteDetector();
        mDetector.start(START_X, STEP);
    }

    @Test
    public void rightwardSwipeNeverInitiates() {
        assertEquals(0, mDetector.onMove(START_X + 350, false));
        assertFalse(mDetector.isActive());
        assertEquals(0, mDetector.onMove(START_X + 50, false));
        assertFalse(mDetector.isActive());
    }

    @Test
    public void movementBelowStepDoesNothing() {
        assertEquals(0, mDetector.onMove(START_X - (STEP - 1), false));
        assertFalse(mDetector.isActive());
    }

    @Test
    public void singleLeftStepDeletesOneWord() {
        assertEquals(-1, mDetector.onMove(START_X - STEP, false));
        assertTrue(mDetector.isActive());
    }

    @Test
    public void multipleLeftStepsDeleteMultipleWords() {
        assertEquals(-3, mDetector.onMove(START_X - 3 * STEP, false));
        assertTrue(mDetector.isActive());
    }

    @Test
    public void stepsAreIncremental() {
        assertEquals(-1, mDetector.onMove(START_X - STEP, false));
        assertEquals(-1, mDetector.onMove(START_X - 2 * STEP, false));
        assertEquals(0, mDetector.onMove(START_X - 2 * STEP - (STEP - 1), false));
        assertEquals(-1, mDetector.onMove(START_X - 3 * STEP, false));
    }

    @Test
    public void rtlFlipsDirection() {
        assertEquals(1, mDetector.onMove(START_X - STEP, true));
        assertTrue(mDetector.isActive());
    }

    @Test
    public void movingBackRightReleasesSelection() {
        assertEquals(-2, mDetector.onMove(START_X - 2 * STEP, false));
        assertEquals(1, mDetector.onMove(START_X - STEP, false));
        assertTrue(mDetector.isActive());
    }

    @Test
    public void cancelStopsGesture() {
        mDetector.onMove(START_X - STEP, false);
        assertTrue(mDetector.isActive());
        mDetector.cancel();
        assertFalse(mDetector.isActive());
        assertEquals(0, mDetector.onMove(START_X + STEP, false));
    }

    @Test
    public void startResetsState() {
        mDetector.onMove(START_X - STEP, false);
        assertTrue(mDetector.isActive());
        mDetector.start(500, STEP);
        assertFalse(mDetector.isActive());
        assertEquals(-1, mDetector.onMove(500 - STEP, false));
    }

    @Test
    public void nonPositiveStepIsGuarded() {
        mDetector.start(START_X, 0);
        assertEquals(-1, mDetector.onMove(START_X - 1, false));
        assertTrue(mDetector.isActive());
    }

    @Test
    public void keyPressJitterDoesNotActivate() {
        for (int dx = -5; dx <= 5; dx++) {
            assertEquals(0, mDetector.onMove(START_X + dx, false));
        }
        assertFalse(mDetector.isActive());
    }

    @Test
    public void rightwardDriftFromKeyDoesNotActivate() {
        assertEquals(0, mDetector.onMove(START_X + 200, false));
        assertEquals(0, mDetector.onMove(START_X + 100, false));
        assertFalse(mDetector.isActive());
    }

    @Test
    public void leftSwipeFromKeyActivatesAfterFullStep() {
        assertEquals(0, mDetector.onMove(START_X - (STEP - 1), false));
        assertFalse(mDetector.isActive());
        assertEquals(-1, mDetector.onMove(START_X - STEP, false));
        assertTrue(mDetector.isActive());
    }

    @Test
    public void restartFromNewKeyResetsOrigin() {
        assertEquals(-1, mDetector.onMove(START_X - STEP, false));
        assertTrue(mDetector.isActive());
        mDetector.start(500, STEP);
        assertFalse(mDetector.isActive());
        assertEquals(0, mDetector.onMove(500 - (STEP - 1), false));
        assertEquals(-1, mDetector.onMove(500 - STEP, false));
        assertTrue(mDetector.isActive());
    }
}
