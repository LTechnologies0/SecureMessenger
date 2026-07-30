package ltechnologies.onionphone.securemessenger.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN attachmentsJson TEXT NOT NULL DEFAULT '[]'")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'TEXT'")
        db.execSQL("ALTER TABLE messages ADD COLUMN payloadJson TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN expireSeconds INTEGER")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS contacts (
                id TEXT NOT NULL PRIMARY KEY,
                protocol TEXT NOT NULL,
                accountId TEXT NOT NULL,
                remoteId TEXT NOT NULL,
                displayName TEXT NOT NULL,
                handle TEXT,
                phone TEXT,
                avatarLocalPath TEXT
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_accountId ON contacts(accountId)")
    }
}
