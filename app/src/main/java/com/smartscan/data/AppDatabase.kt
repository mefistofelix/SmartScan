package com.smartscan.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        DocumentEntity::class, PageEntity::class, TaxonomyNode::class, DocNodeRef::class,
        EndpointEntity::class, UploadEntity::class,
    ],
    version = 1,
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
            Room.databaseBuilder(context, AppDatabase::class.java, "smartscan.db").build()
    }
}
