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

public class SurfaceSwipeRecapitalizeDetectorTest {
    private static final int START_X = 100;
    private static final int START_Y = 200;
    private static final int THRESHOLD = 32;

    private SurfaceSwipeRecapitalizeDetector mDetector;

    @Before
    public void setUp() {
        mDetector = new SurfaceSwipeRecapitalizeDetector();
        mDetector.start(START_X, START_Y, THRESHOLD);
    }

    @Test
    public void upwardSwipeAtThresholdIsRecognizedOnce() {
        assertTrue(mDetector.onMove(START_X, START_Y - THRESHOLD, true));
        assertFalse(mDetector.onMove(START_X, START_Y - 2 * THRESHOLD, true));
    }

    @Test
    public void smallUpwardDriftDoesNotTrigger() {
        assertFalse(mDetector.onMove(START_X, START_Y - (THRESHOLD - 1), true));
    }

    @Test
    public void horizontalMovementRejectsGesture() {
        assertFalse(mDetector.onMove(START_X + THRESHOLD, START_Y, true));
        assertFalse(mDetector.onMove(START_X, START_Y - 2 * THRESHOLD, true));
    }

    @Test
    public void diagonalMovementRejectsGesture() {
        assertFalse(mDetector.onMove(START_X + THRESHOLD, START_Y - THRESHOLD, true));
        assertFalse(mDetector.onMove(START_X, START_Y - 2 * THRESHOLD, true));
    }

    @Test
    public void downwardMovementRejectsGesture() {
        assertFalse(mDetector.onMove(START_X, START_Y + THRESHOLD, true));
        assertFalse(mDetector.onMove(START_X, START_Y - 2 * THRESHOLD, true));
    }

    @Test
    public void multiplePointersRejectGesture() {
        assertFalse(mDetector.onMove(START_X, START_Y - THRESHOLD, false));
        assertFalse(mDetector.onMove(START_X, START_Y - 2 * THRESHOLD, true));
    }

    @Test
    public void cancelRejectsGesture() {
        mDetector.cancel();
        assertFalse(mDetector.onMove(START_X, START_Y - THRESHOLD, true));
    }
}
