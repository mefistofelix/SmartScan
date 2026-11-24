package com.smartscan.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class DocStatus { DRAFT, PROCESSING, READY }

enum class PageFilter(val label: String) {
    ORIGINAL("Originale"), ENHANCED("Migliorato"), GRAY("Grigi"), BW("B/N")
}

enum class NodeKind(val label: String) { CATEGORY("Categorie"), TAG("Tag") }

enum class EndpointType { HTTP, WEBDAV }

enum class UploadFormat(val label: String) { PDF("PDF"), PDF_OCR("PDF ricercabile"), IMAGES("Immagini") }

enum class UploadStatus { QUEUED, RUNNING, DONE, FAILED }

/** What a document becomes when it is saved and sent: chosen in the scanner before «Fine». */
enum class DocOutput(val label: String) { PDF("PDF"), IMAGES("Immagini") }

@Entity(tableName = "documents")
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val notes: String = "",
    val docDate: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val status: DocStatus = DocStatus.DRAFT,
    val ocrText: String = "",
    val output: DocOutput = DocOutput.PDF,
)

@Entity(
    tableName = "pages",
    foreignKeys = [ForeignKey(
        entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["docId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("docId")]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val docId: Long,
    val position: Int,
    /** Upright original photo. */
    val originalPath: String,
    /** Cropped / deskewed / filtered output. */
    val processedPath: String,
    /** Normalized corners "x0,y0,x1,y1,x2,y2,x3,y3" (tl, tr, br, bl) relative to the original. */
    val corners: String,
    val filter: PageFilter,
    val rotation: Int = 0,
    /** Border expansion, fraction of the quad size (negative = shrink). */
    val margin: Float = 0f,
    /** null = OCR not run yet. */
    val ocrText: String? = null,
    val ocrLines: String = "[]",
)

@Entity(
    tableName = "taxonomy",
    foreignKeys = [
        ForeignKey(
            entity = TaxonomyNode::class, parentColumns = ["id"], childColumns = ["parentId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EndpointEntity::class, parentColumns = ["id"], childColumns = ["endpointId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index("parentId"), Index("endpointId")]
)
data class TaxonomyNode(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: NodeKind,
    val name: String,
    val parentId: Long? = null,
    /** null = local node, otherwise the endpoint it was synced from. */
    val endpointId: Long? = null,
    val remoteId: String? = null,
)

@Entity(
    tableName = "doc_nodes",
    primaryKeys = ["docId", "nodeId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TaxonomyNode::class, parentColumns = ["id"], childColumns = ["nodeId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index("nodeId")]
)
data class DocNodeRef(val docId: Long, val nodeId: Long)

@Entity(tableName = "endpoints")
data class EndpointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: EndpointType,
    val url: String,
    /** JSON object of extra HTTP headers (e.g. Authorization). */
    val headers: String = "{}",
    val taxonomyUrl: String? = null,
    val autoUpload: Boolean = true,
    val format: UploadFormat = UploadFormat.PDF_OCR,
    val lastSync: Long? = null,
    val lastSyncError: String? = null,
)

@Entity(
    tableName = "uploads",
    primaryKeys = ["docId", "endpointId"],
    foreignKeys = [
        ForeignKey(
            entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["docId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = EndpointEntity::class, parentColumns = ["id"], childColumns = ["endpointId"],
            onDelete = ForeignKey.CASCADE
        ),
    ],
    indices = [Index("endpointId")]
)
data class UploadEntity(
    val docId: Long,
    val endpointId: Long,
    val status: UploadStatus,
    val message: String = "",
    val remoteRef: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class DocListRow(
    @Embedded val doc: DocumentEntity,
    val thumb: String?,
    val pageCount: Int,
)
