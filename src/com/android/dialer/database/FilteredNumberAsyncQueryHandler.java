/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.dialer.database;

import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabaseCorruptException;
import android.net.Uri;
import android.telephony.PhoneNumberUtils;

import com.android.contacts.common.util.PhoneNumberHelper;
import com.android.dialer.database.FilteredNumberContract.FilteredNumber;
import com.android.dialer.database.FilteredNumberContract.FilteredNumberColumns;
import com.android.dialer.database.FilteredNumberContract.FilteredNumberSources;
import com.android.dialer.database.FilteredNumberContract.FilteredNumberTypes;

public class FilteredNumberAsyncQueryHandler extends AsyncQueryHandler {
    private static final int NO_TOKEN = 0;

    public FilteredNumberAsyncQueryHandler(ContentResolver cr) {
        super(cr);
    }

    /**
     * Methods for FilteredNumberAsyncQueryHandler result returns.
     */
    private static abstract class Listener {
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
        }
        protected void onInsertComplete(int token, Object cookie, Uri uri) {
        }
        protected void onUpdateComplete(int token, Object cookie, int result) {
        }
        protected void onDeleteComplete(int token, Object cookie, int result) {
        }
    }

    public interface OnCheckBlockedListener {
        /**
         * Invoked after querying if a number is blocked.
         * @param id The ID of the row if blocked, null otherwise.
         */
        public void onCheckComplete(Integer id);
    }

    public interface OnBlockNumberListener {
        /**
         * Invoked after inserting a blocked number.
         * @param uri The uri of the newly created row.
         */
        public void onBlockComplete(Uri uri);
    }

    public interface OnUnblockNumberListener {
        /**
         * Invoked after removing a blocked number
         * @param rows The number of rows affected (expected value 1).
         * @param values The deleted data (used for restoration).
         */
        public void onUnblockComplete(int rows, ContentValues values);
    }

    public interface OnHasBlockedNumbersListener {
        /**
         * @param hasBlockedNumbers {@code true} if any blocked numbers are stored.
         *     {@code false} otherwise.
         */
        public void onHasBlockedNumbers(boolean hasBlockedNumbers);
    }

    @Override
    protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
        if (cookie != null) {
            ((Listener) cookie).onQueryComplete(token, cookie, cursor);
        }
    }

    @Override
    protected void onInsertComplete(int token, Object cookie, Uri uri) {
        if (cookie != null) {
            ((Listener) cookie).onInsertComplete(token, cookie, uri);
        }
    }

    @Override
    protected void onUpdateComplete(int token, Object cookie, int result) {
        if (cookie != null) {
            ((Listener) cookie).onUpdateComplete(token, cookie, result);
        }
    }

    @Override
    protected void onDeleteComplete(int token, Object cookie, int result) {
        if (cookie != null) {
            ((Listener) cookie).onDeleteComplete(token, cookie, result);
        }
    }

    private static Uri getContentUri(Integer id) {
        Uri uri = FilteredNumber.CONTENT_URI;
        if (id != null) {
            uri = ContentUris.withAppendedId(uri, id);
        }
        return uri;
    }

    public final void incrementFilteredCount(Integer id) {
        startUpdate(NO_TOKEN, null,
                ContentUris.withAppendedId(FilteredNumber.CONTENT_URI_INCREMENT_FILTERED_COUNT, id),
                null, null, null);
    }

    public final void hasBlockedNumbersAsync(final OnHasBlockedNumbersListener listener) {
        startQuery(NO_TOKEN,
                new Listener() {
                    @Override
                    protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                        listener.onHasBlockedNumbers(cursor.getCount() > 0);
                    }
                },
                getContentUri(null),
                new String[]{ FilteredNumberColumns._ID },
                FilteredNumberColumns.TYPE + "=" + FilteredNumberTypes.BLOCKED_NUMBER,
                null,
                null);
    }

    /**
     * Check if the number + country iso given has been blocked.
     * This method normalizes the number for the lookup if normalizedNumber is null.
     * @return {@code true} if the number was invalid and couldn't be checked,
     * {@code false} otherwise,
     */
    public final boolean startBlockedQuery(final OnCheckBlockedListener listener,
                                        String normalizedNumber, String number, String countryIso) {
        if (normalizedNumber == null) {
            normalizedNumber = PhoneNumberUtils.formatNumberToE164(number, countryIso);
            if (normalizedNumber == null) {
                return true;
            }
        }
        startBlockedQuery(listener, normalizedNumber);
        return false;
    }

    /**
     * Check if the normalized number given has been blocked.
     */
    public final void startBlockedQuery(final OnCheckBlockedListener listener,
                                        String normalizedNumber) {
        startQuery(NO_TOKEN,
                new Listener() {
                    @Override
                    protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
                        if (cursor.getCount() != 1) {
                            listener.onCheckComplete(null);
                            return;
                        }
                        cursor.moveToFirst();
                        if (cursor.getInt(cursor.getColumnIndex(FilteredNumberColumns.TYPE))
                                != FilteredNumberTypes.BLOCKED_NUMBER) {
                            listener.onCheckComplete(null);
                            return;
                        }
                        listener.onCheckComplete(
                                cursor.getInt(cursor.getColumnIndex(FilteredNumberColumns._ID)));
                    }
                },
                getContentUri(null),
                new String[]{FilteredNumberColumns._ID, FilteredNumberColumns.TYPE},
                FilteredNumberColumns.NORMALIZED_NUMBER + " = ?",
                new String[]{normalizedNumber},
                null);
    }

    public final void blockNumber(
            final OnBlockNumberListener listener, String number, String countryIso) {
        blockNumber(null, number, countryIso);
    }

    /**
     * Add a number manually blocked by the user.
     */
    public final void blockNumber(
            final OnBlockNumberListener listener,
            String normalizedNumber,
            String number,
            String countryIso) {
        if (normalizedNumber == null) {
            normalizedNumber = PhoneNumberUtils.formatNumberToE164(number, countryIso);
        }
        ContentValues v = new ContentValues();
        v.put(FilteredNumberColumns.NORMALIZED_NUMBER, normalizedNumber);
        v.put(FilteredNumberColumns.NUMBER, number);
        v.put(FilteredNumberColumns.COUNTRY_ISO, countryIso);
        v.put(FilteredNumberColumns.TYPE, FilteredNumberTypes.BLOCKED_NUMBER);
        v.put(FilteredNumberColumns.SOURCE, FilteredNumberSources.USER);
        blockNumber(listener, v);
    }

    /**
     * Block a number with specified ContentValues. Can be manually added or a restored row
     * from performing the 'undo' action after unblocking.
     */
    public final void blockNumber(final OnBlockNumberListener listener, ContentValues values) {
        startInsert(NO_TOKEN,
                new Listener() {
                    @Override
                    public void onInsertComplete(int token, Object cookie, Uri uri) {
                        if (listener != null ) {
                            listener.onBlockComplete(uri);
                        }
                    }
                }, getContentUri(null), values);
    }

    /**
     * Removes row from database.
     * Caller should call {@link FilteredNumberAsyncQueryHandler#startBlockedQuery} first.
     * @param id The ID of row to remove, from {@link FilteredNumberAsyncQueryHandler#startBlockedQuery}.
     */
    public final void unblock(final OnUnblockNumberListener listener, Integer id) {
        if (id == null) {
            throw new IllegalArgumentException("Null id passed into unblock");
        }
        unblock(listener, getContentUri(id));
    }

    /**
     * Removes row from database.
     * @param uri The uri of row to remove, from
     *         {@link FilteredNumberAsyncQueryHandler#blockNumber}.
     */
    public final void unblock(final OnUnblockNumberListener listener, final Uri uri) {
        startQuery(NO_TOKEN, new Listener() {
            @Override
            public void onQueryComplete(int token, Object cookie, Cursor cursor) {
                if (cursor.getCount() != 1) {
                    throw new SQLiteDatabaseCorruptException
                            ("Returned " + cursor.getCount() + " rows for uri "
                                    + uri + "where 1 expected.");
                }
                cursor.moveToFirst();
                final ContentValues values = new ContentValues();
                DatabaseUtils.cursorRowToContentValues(cursor, values);
                values.remove(FilteredNumberColumns._ID);

                startDelete(NO_TOKEN, new Listener() {
                    @Override
                    public void onDeleteComplete(int token, Object cookie, int result) {
                        if (listener != null) {
                            listener.onUnblockComplete(result, values);
                        }
                    }
                }, uri, null, null);
            }
        }, uri, null, null, null, null);
    }
}
