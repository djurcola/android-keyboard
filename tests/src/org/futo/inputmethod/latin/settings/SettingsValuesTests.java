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

package org.futo.inputmethod.latin.settings;

import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.futo.inputmethod.latin.InputTestsBase;

public class SettingsValuesTests extends InputTestsBase {
    public void testSurfaceSwipeRecapitalizePreferenceDefaultsToEnabled() {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(getService());
        final boolean hadPreviousValue = prefs.contains(Settings.PREF_SURFACE_SWIPE_RECAPITALIZE);
        final boolean previousValue = prefs.getBoolean(Settings.PREF_SURFACE_SWIPE_RECAPITALIZE, true);
        try {
            prefs.edit().remove(Settings.PREF_SURFACE_SWIPE_RECAPITALIZE).commit();
            assertTrue(Settings.getInstance().getCurrent().mSurfaceSwipeRecapitalizeEnabled);

            prefs.edit().putBoolean(Settings.PREF_SURFACE_SWIPE_RECAPITALIZE, false).commit();
            assertFalse(Settings.getInstance().getCurrent().mSurfaceSwipeRecapitalizeEnabled);
        } finally {
            final SharedPreferences.Editor editor = prefs.edit();
            if (hadPreviousValue) {
                editor.putBoolean(Settings.PREF_SURFACE_SWIPE_RECAPITALIZE, previousValue);
            } else {
                editor.remove(Settings.PREF_SURFACE_SWIPE_RECAPITALIZE);
            }
            editor.commit();
        }
    }

    public void testSurfaceSwipeRightRecapitalizePreferenceDefaultsToEnabled() {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(getService());
        final boolean hadPreviousValue =
                prefs.contains(Settings.PREF_SURFACE_SWIPE_RIGHT_RECAPITALIZE);
        final boolean previousValue = prefs.getBoolean(
                Settings.PREF_SURFACE_SWIPE_RIGHT_RECAPITALIZE, true);
        try {
            prefs.edit().remove(Settings.PREF_SURFACE_SWIPE_RIGHT_RECAPITALIZE).commit();
            assertTrue(Settings.getInstance().getCurrent().mSurfaceSwipeRightRecapitalizeEnabled);

            prefs.edit().putBoolean(Settings.PREF_SURFACE_SWIPE_RIGHT_RECAPITALIZE, false).commit();
            assertFalse(Settings.getInstance().getCurrent().mSurfaceSwipeRightRecapitalizeEnabled);
        } finally {
            final SharedPreferences.Editor editor = prefs.edit();
            if (hadPreviousValue) {
                editor.putBoolean(Settings.PREF_SURFACE_SWIPE_RIGHT_RECAPITALIZE, previousValue);
            } else {
                editor.remove(Settings.PREF_SURFACE_SWIPE_RIGHT_RECAPITALIZE);
            }
            editor.commit();
        }
    }

    public void testHoldShiftRecapitalizePreferenceDefaultsToDisabled() {
        final SharedPreferences prefs =
                PreferenceManager.getDefaultSharedPreferences(getService());
        final boolean hadPreviousValue = prefs.contains(Settings.PREF_HOLD_SHIFT_RECAPITALIZE);
        final boolean previousValue = prefs.getBoolean(Settings.PREF_HOLD_SHIFT_RECAPITALIZE, false);
        try {
            prefs.edit().remove(Settings.PREF_HOLD_SHIFT_RECAPITALIZE).commit();
            assertFalse(Settings.getInstance().getCurrent().mHoldShiftRecapitalizeEnabled);

            prefs.edit().putBoolean(Settings.PREF_HOLD_SHIFT_RECAPITALIZE, true).commit();
            assertTrue(Settings.getInstance().getCurrent().mHoldShiftRecapitalizeEnabled);
        } finally {
            final SharedPreferences.Editor editor = prefs.edit();
            if (hadPreviousValue) {
                editor.putBoolean(Settings.PREF_HOLD_SHIFT_RECAPITALIZE, previousValue);
            } else {
                editor.remove(Settings.PREF_HOLD_SHIFT_RECAPITALIZE);
            }
            editor.commit();
        }
    }
}
