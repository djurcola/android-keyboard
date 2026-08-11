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

/** Detects a deliberate, single-pointer upward surface swipe. */
public final class SurfaceSwipeRecapitalizeDetector {
    private int mStartX;
    private int mStartY;
    private int mThreshold;
    private boolean mEligible;

    public void start(final int startX, final int startY, final int threshold) {
        mStartX = startX;
        mStartY = startY;
        mThreshold = threshold > 0 ? threshold : 1;
        mEligible = true;
    }

    public void cancel() {
        mEligible = false;
    }

    /**
     * Returns true once an upward swipe has crossed the threshold. Horizontal, diagonal, and
     * downward movement permanently reject this gesture.
     */
    public boolean onMove(final int x, final int y, final boolean isSinglePointer) {
        if (!mEligible || !isSinglePointer) {
            mEligible = false;
            return false;
        }
        final int horizontalDistance = Math.abs(x - mStartX);
        final int upwardDistance = mStartY - y;
        if (upwardDistance <= 0 || horizontalDistance >= upwardDistance) {
            mEligible = false;
            return false;
        }
        if (upwardDistance < mThreshold) {
            return false;
        }
        mEligible = false;
        return true;
    }
}
