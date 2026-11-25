package com.smartscan.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert suspend fun insert(d: DocumentEntity): Long
    @Update suspend fun update(d: DocumentEntity)
    @Query("DELETE FROM documents WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: Long): DocumentEntity?
    @Query("SELECT * FROM documents WHERE id = :id") fun observe(id: Long): Flow<DocumentEntity?>

    @Query(
        """SELECT d.*,
           (SELECT p.processedPath FROM pages p WHERE p.docId = d.id ORDER BY p.position LIMIT 1) AS thumb,
           (SELECT COUNT(*) FROM pages p WHERE p.docId = d.id) AS pageCount
           FROM documents d ORDER BY d.sortKey DESC, d.id DESC"""
    )
    fun observeList(): Flow<List<DocListRow>>

    @Query("UPDATE documents SET sortKey = :key WHERE id = :id") suspend fun setSortKey(id: Long, key: Long)

    @Query("SELECT id FROM documents WHERE status = 'DRAFT' AND createdAt < :before AND id != :keep AND id NOT IN (SELECT docId FROM pages)")
    suspend fun emptyDraftIds(before: Long, keep: Long): List<Long>
}

@Dao
interface PageDao {
    @Insert suspend fun insert(p: PageEntity): Long
    @Update suspend fun update(p: PageEntity)
    @Update suspend fun updateAll(p: List<PageEntity>)
    @Delete suspend fun delete(p: PageEntity)
    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY position")
    fun observe(docId: Long): Flow<List<PageEntity>>
    @Query("SELECT * FROM pages WHERE docId = :docId ORDER BY position")
    suspend fun list(docId: Long): List<PageEntity>
    @Query("SELECT * FROM pages WHERE id = :id") suspend fun get(id: Long): PageEntity?
    @Query("SELECT MAX(position) FROM pages WHERE docId = :docId") suspend fun maxPosition(docId: Long): Int?
    @Query("SELECT COUNT(*) FROM pages WHERE docId = :docId") suspend fun count(docId: Long): Int
}

@Dao
interface TaxonomyDao {
    @Insert suspend fun insert(n: TaxonomyNode): Long
    @Update suspend fun update(n: TaxonomyNode)
    @Delete suspend fun delete(n: TaxonomyNode)
    @Query("SELECT * FROM taxonomy ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<TaxonomyNode>>
    @Query("SELECT * FROM taxonomy") suspend fun all(): List<TaxonomyNode>
    @Query("SELECT * FROM taxonomy WHERE endpointId = :endpointId")
    suspend fun forEndpoint(endpointId: Long): List<TaxonomyNode>

    @Query("SELECT * FROM doc_nodes") fun observeRefs(): Flow<List<DocNodeRef>>
    @Query("SELECT nodeId FROM doc_nodes WHERE docId = :docId") fun observeNodeIds(docId: Long): Flow<List<Long>>
    @Query("SELECT nodeId FROM doc_nodes WHERE docId = :docId") suspend fun nodeIds(docId: Long): List<Long>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertRefs(refs: List<DocNodeRef>)
    @Query("DELETE FROM doc_nodes WHERE docId = :docId") suspend fun clearRefs(docId: Long)
}

@Dao
interface EndpointDao {
    @Insert suspend fun insert(e: EndpointEntity): Long
    @Update suspend fun update(e: EndpointEntity)
    @Delete suspend fun delete(e: EndpointEntity)
    @Query("SELECT * FROM endpoints ORDER BY name COLLATE NOCASE") fun observeAll(): Flow<List<EndpointEntity>>
    @Query("SELECT * FROM endpoints") suspend fun all(): List<EndpointEntity>
    @Query("SELECT * FROM endpoints WHERE id = :id") suspend fun get(id: Long): EndpointEntity?
}

@Dao
interface UploadDao {
    @Upsert suspend fun upsert(u: UploadEntity)
    @Query("SELECT * FROM uploads") fun observeAll(): Flow<List<UploadEntity>>
    @Query("SELECT * FROM uploads WHERE docId = :docId") fun observeForDoc(docId: Long): Flow<List<UploadEntity>>
}
