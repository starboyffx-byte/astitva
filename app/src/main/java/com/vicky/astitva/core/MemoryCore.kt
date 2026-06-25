package com.vicky.astitva.core

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log

data class SessionMessage(val sender: String, val msg: String, val isUser: Boolean, val type: String)

class MemoryCore(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val TAG = "MemoryCore"
        private const val DATABASE_VERSION = 3
        private const val DATABASE_NAME = "AstitvaBrain.db"
        
        const val TABLE_MEMORIES = "memories"
        const val COLUMN_ID = "id"
        const val COLUMN_FACT = "fact"
        const val COLUMN_TIMESTAMP = "timestamp"
        
        const val TABLE_SESSIONS = "chat_sessions"
        const val COLUMN_SESSION_ID = "session_id"
        const val COLUMN_SESSION_TITLE = "title"
        
        const val TABLE_MESSAGES = "chat_session_messages"
        const val COLUMN_MSG_SENDER = "sender"
        const val COLUMN_MSG_TEXT = "msg"
        const val COLUMN_MSG_IS_USER = "is_user"
        const val COLUMN_MSG_TYPE = "msg_type"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createMemoriesTable = ("CREATE TABLE " + TABLE_MEMORIES + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_FACT + " TEXT,"
                + COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")")
        db.execSQL(createMemoriesTable)
        
        // Create FTS5 virtual table for lightning-fast keyword searches with fallbacks
        try {
            db.execSQL("CREATE VIRTUAL TABLE memories_fts USING fts5(fact)")
            Log.d(TAG, "Successfully initialized SQLite FTS5 table.")
        } catch (e: Exception) {
            Log.w(TAG, "SQLite FTS5 unsupported, falling back to FTS4 virtual table: ${e.message}")
            try {
                db.execSQL("CREATE VIRTUAL TABLE memories_fts USING fts4(fact)")
                Log.d(TAG, "Successfully initialized SQLite FTS4 table.")
            } catch (e2: Exception) {
                Log.e(TAG, "Both FTS5 and FTS4 initialization failed. Full-text search will run on raw LIKE matching.", e2)
            }
        }
        
        val createSessionsTable = ("CREATE TABLE " + TABLE_SESSIONS + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_SESSION_ID + " TEXT UNIQUE,"
                + COLUMN_SESSION_TITLE + " TEXT,"
                + COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")")
        db.execSQL(createSessionsTable)
        
        val createMessagesTable = ("CREATE TABLE " + TABLE_MESSAGES + "("
                + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + COLUMN_SESSION_ID + " TEXT,"
                + COLUMN_MSG_SENDER + " TEXT,"
                + COLUMN_MSG_TEXT + " TEXT,"
                + COLUMN_MSG_IS_USER + " INTEGER,"
                + COLUMN_MSG_TYPE + " TEXT,"
                + COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")")
        db.execSQL(createMessagesTable)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSIONS)
            db.execSQL("DROP TABLE IF EXISTS " + TABLE_MESSAGES)
            
            val createSessionsTable = ("CREATE TABLE " + TABLE_SESSIONS + "("
                    + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + COLUMN_SESSION_ID + " TEXT UNIQUE,"
                    + COLUMN_SESSION_TITLE + " TEXT,"
                    + COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")")
            db.execSQL(createSessionsTable)
            
            val createMessagesTable = ("CREATE TABLE " + TABLE_MESSAGES + "("
                    + COLUMN_ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + COLUMN_SESSION_ID + " TEXT,"
                    + COLUMN_MSG_SENDER + " TEXT,"
                    + COLUMN_MSG_TEXT + " TEXT,"
                    + COLUMN_MSG_IS_USER + " INTEGER,"
                    + COLUMN_MSG_TYPE + " TEXT,"
                    + COLUMN_TIMESTAMP + " DATETIME DEFAULT CURRENT_TIMESTAMP" + ")")
            db.execSQL(createMessagesTable)
        }
        if (oldVersion < 3) {
            // Perform migration to version 3 to add FTS table and index existing memories
            try {
                db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts5(fact)")
                Log.d(TAG, "Migrated: FTS5 virtual table built.")
            } catch (e: Exception) {
                try {
                    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS memories_fts USING fts4(fact)")
                    Log.d(TAG, "Migrated: FTS4 virtual table built.")
                } catch (e2: Exception) {
                    Log.e(TAG, "Failed migrating to FTS index: ${e2.message}")
                }
            }
            try {
                db.execSQL("INSERT INTO memories_fts(fact) SELECT fact FROM memories")
                Log.d(TAG, "Successfully indexed old memories in FTS database.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to copy old memories to FTS search table: ${e.message}")
            }
        }
    }

    fun remember(fact: String) {
        if (fact.isBlank()) return
        val db = this.writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put(COLUMN_FACT, fact)
            }
            db.insert(TABLE_MEMORIES, null, values)
            
            val ftsValues = ContentValues().apply {
                put("fact", fact)
            }
            db.insert("memories_fts", null, ftsValues)
            db.setTransactionSuccessful()
        } catch (e: Exception) {
            Log.e(TAG, "Error writing memory fact: ${e.message}", e)
        } finally {
            db.endTransaction()
            db.close()
        }
    }

    fun getAllMemories(limit: Int = 10): String {
        val db = this.readableDatabase
        val memories = java.lang.StringBuilder()
        var cursor: android.database.Cursor? = null
        try {
            cursor = db.rawQuery("SELECT fact FROM $TABLE_MEMORIES ORDER BY timestamp DESC LIMIT ?", arrayOf(limit.toString()))
            var count = 1
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    memories.append("$count. ${cursor.getString(0)}\n")
                    count++
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error reading all memories: ${e.message}", e)
        } finally {
            cursor?.close()
            db.close()
        }
        return if (memories.isEmpty()) "No explicit memories recorded yet." else memories.toString()
    }

    fun findRelevantMemories(query: String, limit: Int = 5): String {
        val db = this.readableDatabase
        val cleanQuery = query.replace("'", "''").trim()
        if (cleanQuery.isEmpty()) return ""

        val escapedQuery = cleanQuery.replace(Regex("[*?:\"]"), " ")
        val memories = java.lang.StringBuilder()
        var cursor: android.database.Cursor? = null
        
        try {
            val tokens = escapedQuery.split(Regex("\\s+")).filter { it.length > 2 }
            val matchExpr = if (tokens.isNotEmpty()) {
                tokens.joinToString(" OR ") { "$it*" }
            } else {
                "$escapedQuery*"
            }

            cursor = db.rawQuery("SELECT fact FROM memories_fts WHERE fact MATCH ? LIMIT ?", arrayOf(matchExpr, limit.toString()))
            if (cursor != null && cursor.moveToFirst()) {
                var count = 1
                do {
                    memories.append("$count. ${cursor.getString(0)}\n")
                    count++
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.w(TAG, "FTS query search failed, falling back to LIKE matches: ${e.message}")
            try {
                cursor?.close()
                cursor = db.rawQuery("SELECT fact FROM $TABLE_MEMORIES WHERE fact LIKE ? ORDER BY timestamp DESC LIMIT ?", arrayOf("%$cleanQuery%", limit.toString()))
                var count = 1
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        memories.append("$count. ${cursor.getString(0)}\n")
                        count++
                    } while (cursor.moveToNext())
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback memory queries failed: ${e2.message}", e2)
            }
        } finally {
            cursor?.close()
            db.close()
        }
        return memories.toString()
    }

    // --- SESSION METHODS ---
    fun createSession(sessionId: String, title: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_SESSION_ID, sessionId)
            put(COLUMN_SESSION_TITLE, title)
        }
        db.insertWithOnConflict(TABLE_SESSIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.close()
    }

    fun deleteSession(sessionId: String) {
        val db = this.writableDatabase
        db.delete(TABLE_SESSIONS, "$COLUMN_SESSION_ID = ?", arrayOf(sessionId))
        db.delete(TABLE_MESSAGES, "$COLUMN_SESSION_ID = ?", arrayOf(sessionId))
        db.close()
    }

    fun renameSession(sessionId: String, newTitle: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_SESSION_TITLE, newTitle)
        }
        db.update(TABLE_SESSIONS, values, "$COLUMN_SESSION_ID = ?", arrayOf(sessionId))
        db.close()
    }

    fun getAllSessions(): List<Pair<String, String>> {
        val sessions = mutableListOf<Pair<String, String>>()
        val db = this.readableDatabase
        val cursor = db.rawQuery("SELECT $COLUMN_SESSION_ID, $COLUMN_SESSION_TITLE FROM $TABLE_SESSIONS ORDER BY timestamp DESC", null)
        if (cursor != null && cursor.moveToFirst()) {
            do {
                sessions.add(Pair(cursor.getString(0), cursor.getString(1)))
            } while (cursor.moveToNext())
        }
        cursor?.close()
        db.close()
        return sessions
    }

    // --- MESSAGE METHODS WITH MEMORY SAFETY PAGINATION (MAX 100 FOR RENDER SAFETY) ---
    fun saveMessage(sessionId: String, sender: String, msg: String, isUser: Boolean, type: String) {
        val db = this.writableDatabase
        val values = ContentValues().apply {
            put(COLUMN_SESSION_ID, sessionId)
            put(COLUMN_MSG_SENDER, sender)
            put(COLUMN_MSG_TEXT, msg)
            put(COLUMN_MSG_IS_USER, if (isUser) 1 else 0)
            put(COLUMN_MSG_TYPE, type)
        }
        db.insert(TABLE_MESSAGES, null, values)
        db.close()
    }

    fun getSessionMessages(sessionId: String): List<SessionMessage> {
        val messages = mutableListOf<SessionMessage>()
        val db = this.readableDatabase
        var cursor: android.database.Cursor? = null
        try {
            cursor = db.rawQuery(
                "SELECT $COLUMN_MSG_SENDER, $COLUMN_MSG_TEXT, $COLUMN_MSG_IS_USER, $COLUMN_MSG_TYPE FROM " +
                "(SELECT * FROM $TABLE_MESSAGES WHERE $COLUMN_SESSION_ID = ? ORDER BY id DESC LIMIT 100) ORDER BY id ASC",
                arrayOf(sessionId)
            )
            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val sender = cursor.getString(0)
                    val msg = cursor.getString(1)
                    val isUser = cursor.getInt(2) == 1
                    val type = cursor.getString(3)
                    messages.add(SessionMessage(sender, msg, isUser, type))
                } while (cursor.moveToNext())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching session messages: ${e.message}", e)
        } finally {
            cursor?.close()
            db.close()
        }
        return messages
    }
    
    fun clearSessionMessages(sessionId: String) {
        val db = this.writableDatabase
        db.delete(TABLE_MESSAGES, "$COLUMN_SESSION_ID = ?", arrayOf(sessionId))
        db.close()
    }
}