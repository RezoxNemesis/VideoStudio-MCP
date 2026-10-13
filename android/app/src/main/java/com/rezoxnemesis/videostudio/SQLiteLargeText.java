package com.rezoxnemesis.videostudio;

import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDoneException;
import android.database.sqlite.SQLiteStatement;

/** Read an owned single TEXT value without putting it into Android's 2 MiB CursorWindow.
 * Callers supply static SQL and bound identifiers; existing rows and schemas stay intact. */
final class SQLiteLargeText {
    private SQLiteLargeText() {}
    static String read(SQLiteDatabase db, String sql, String... arguments) {
        try (SQLiteStatement statement = db.compileStatement(sql)) {
            for (int i = 0; i < arguments.length; i++) statement.bindString(i + 1, arguments[i]);
            try { return statement.simpleQueryForString(); }
            catch (SQLiteDoneException absent) { return null; }
        }
    }
}
