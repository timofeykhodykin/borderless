package app.borderless.data

/**
 * The part of an SQLite connection the statistics use (the names are Android's `SQLiteDatabase`, which the Android
 * driver passes through; the Mac's wraps the system's sqlite3). Arguments are bound, never written into the SQL.
 */
interface SqlDatabase {
    fun execSQL(sql: String)
    fun execSQL(sql: String, args: Array<Any?>)
    fun rawQuery(sql: String, args: Array<String>): SqlCursor
    fun insert(table: String, nullColumnHack: String?, values: SqlValues): Long
    /** Rows deleted. */
    fun delete(table: String, whereClause: String?, whereArgs: Array<String>?): Int
    fun beginTransaction()
    fun setTransactionSuccessful()
    fun endTransaction()
    /** `PRAGMA user_version`: the schema's version. */
    var version: Int
}

interface SqlCursor : AutoCloseable {
    fun moveToNext(): Boolean
    fun moveToFirst(): Boolean
    fun isNull(i: Int): Boolean
    fun getLong(i: Int): Long
    fun getInt(i: Int): Int
    fun getDouble(i: Int): Double
    fun getString(i: Int): String?
    val columnCount: Int
    /** The number of rows when known (a capacity hint), else 0. */
    val count: Int
    val columnNames: Array<String>
}

/** A row to insert (`ContentValues`): null values are stored as NULL. */
class SqlValues {
    val entries = LinkedHashMap<String, Any?>()

    fun put(key: String, value: Long?) { entries[key] = value }
    fun put(key: String, value: Int?) { entries[key] = value?.toLong() }
    fun put(key: String, value: Double?) { entries[key] = value }
    fun put(key: String, value: String?) { entries[key] = value }
    fun putNull(key: String) { entries[key] = null }
}
