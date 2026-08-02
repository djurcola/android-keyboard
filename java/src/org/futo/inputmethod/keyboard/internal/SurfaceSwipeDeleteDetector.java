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

public final class SurfaceSwipeDeleteDetector {
    private int mStep;
    private int mStartX;
    private boolean mActive;

    public void start(final int startX, final int step) {
        mStartX = startX;
        mStep = step > 0 ? step : 1;
        mActive = false;
    }

    public boolean isActive() {
        return mActive;
    }

    public void cancel() {
        mActive = false;
    }

    public int onMove(final int x, final boolean isRTL) {
        final int steps = (x - mStartX) / mStep;
        if (!mActive) {
            if (steps >= 0) {
                return 0;
            }
            mActive = true;
        }
        if (steps == 0) {
            return 0;
        }
        mStartX += steps * mStep;
        return isRTL ? -steps : steps;
    }
}
