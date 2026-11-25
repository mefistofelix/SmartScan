package com.smartscan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DocumentEntity::class, PageEntity::class, TaxonomyNode::class, DocNodeRef::class,
        EndpointEntity::class, UploadEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documents(): DocumentDao
    abstract fun pages(): PageDao
    abstract fun taxonomy(): TaxonomyDao
    abstract fun endpoints(): EndpointDao
    abstract fun uploads(): UploadDao

    companion object {
        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "smartscan.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE documents ADD COLUMN output TEXT NOT NULL DEFAULT 'PDF'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pages ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pages ADD COLUMN lat REAL")
                db.execSQL("ALTER TABLE pages ADD COLUMN lon REAL")
                db.execSQL("ALTER TABLE pages ADD COLUMN codes TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}
