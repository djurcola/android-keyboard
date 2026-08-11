/*
 * Copyright (C) 2026 The Android Open Source Project
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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

public class SurfaceSwipeRightRecapitalizeDetectorTest {
    private static final int START_X = 100;
    private static final int START_Y = 200;
    private static final int THRESHOLD = 32;

    private SurfaceSwipeRightRecapitalizeDetector mDetector;

    @Before
    public void setUp() {
        mDetector = new SurfaceSwipeRightRecapitalizeDetector();
        mDetector.start(START_X, START_Y, THRESHOLD);
    }

    @Test
    public void rightwardSwipeAtThresholdIsRecognizedOnce() {
        assertTrue(mDetector.onMove(START_X + THRESHOLD, START_Y, true));
        assertFalse(mDetector.onMove(START_X + 2 * THRESHOLD, START_Y, true));
    }

    @Test
    public void detectorNotStartedForDisabledSettingDoesNotRecognizeSwipe() {
        final SurfaceSwipeRightRecapitalizeDetector disabledDetector =
                new SurfaceSwipeRightRecapitalizeDetector();
        assertFalse(disabledDetector.onMove(START_X + THRESHOLD, START_Y, true));
    }

    @Test
    public void smallRightwardDriftDoesNotTrigger() {
        assertFalse(mDetector.onMove(START_X + THRESHOLD - 1, START_Y, true));
    }

    @Test
    public void leftwardAndVerticalMovementRejectGesture() {
        assertFalse(mDetector.onMove(START_X - THRESHOLD, START_Y, true));
        assertFalse(mDetector.onMove(START_X + 2 * THRESHOLD, START_Y, true));

        mDetector.start(START_X, START_Y, THRESHOLD);
        assertFalse(mDetector.onMove(START_X, START_Y - THRESHOLD, true));
    }

    @Test
    public void diagonalAndMultiplePointerMovementRejectGesture() {
        assertFalse(mDetector.onMove(START_X + THRESHOLD, START_Y - THRESHOLD, true));
        mDetector.start(START_X, START_Y, THRESHOLD);
        assertFalse(mDetector.onMove(START_X + THRESHOLD, START_Y, false));
    }

    @Test
    public void rightUpAndLeftPathsAreMutuallyExclusive() {
        final SurfaceSwipeRecapitalizeDetector upDetector =
                new SurfaceSwipeRecapitalizeDetector();
        upDetector.start(START_X, START_Y, THRESHOLD);
        assertFalse(upDetector.onMove(START_X + THRESHOLD, START_Y, true));
        assertTrue(mDetector.onMove(START_X + THRESHOLD, START_Y, true));

        mDetector.start(START_X, START_Y, THRESHOLD);
        upDetector.start(START_X, START_Y, THRESHOLD);
        assertTrue(upDetector.onMove(START_X, START_Y - THRESHOLD, true));
        assertFalse(mDetector.onMove(START_X, START_Y - THRESHOLD, true));

        mDetector.start(START_X, START_Y, THRESHOLD);
        final SurfaceSwipeDeleteDetector deleteDetector = new SurfaceSwipeDeleteDetector();
        deleteDetector.start(START_X, THRESHOLD, 0);
        assertFalse(mDetector.onMove(START_X - THRESHOLD, START_Y, true));
        assertTrue(deleteDetector.onMove(START_X - THRESHOLD, false, 0) < 0);

        mDetector.start(START_X, START_Y, THRESHOLD);
        deleteDetector.start(START_X, THRESHOLD, 0);
        assertTrue(mDetector.onMove(START_X + THRESHOLD, START_Y, true));
        deleteDetector.onMove(START_X + THRESHOLD, false, 0);
        assertFalse(deleteDetector.isActive());
    }
}
