package app.yttopc.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable(with = ItemStatusSerializer::class)
enum class ItemStatus {
    PENDING,
    DOWNLOADING,
    DONE,
    SKIPPED,
    FAILED,
    EXPANDED,
    UNKNOWN,
}

object ItemStatusSerializer : KSerializer<ItemStatus> {
    override val descriptor = PrimitiveSerialDescriptor("ItemStatus", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ItemStatus) {
        encoder.encodeString(
            when (value) {
                ItemStatus.PENDING -> "pending"
                ItemStatus.DOWNLOADING -> "downloading"
                ItemStatus.DONE -> "done"
                ItemStatus.SKIPPED -> "skipped"
                ItemStatus.FAILED -> "failed"
                ItemStatus.EXPANDED -> "expanded"
                ItemStatus.UNKNOWN -> "unknown"
            },
        )
    }

    override fun deserialize(decoder: Decoder): ItemStatus {
        return when (decoder.decodeString().lowercase()) {
            "pending" -> ItemStatus.PENDING
            "downloading" -> ItemStatus.DOWNLOADING
            "done" -> ItemStatus.DONE
            "skipped" -> ItemStatus.SKIPPED
            "failed" -> ItemStatus.FAILED
            "expanded" -> ItemStatus.EXPANDED
            else -> ItemStatus.UNKNOWN
        }
    }
}

@Serializable
sealed class Command {
    abstract val id: String
    abstract val at: String

    @Serializable
    @SerialName("enqueue-video")
    data class EnqueueVideo(
        override val id: String,
        override val at: String,
        val url: String,
        val videoId: String,
        val folder: String,
    ) : Command()

    @Serializable
    @SerialName("enqueue-playlist")
    data class EnqueuePlaylist(
        override val id: String,
        override val at: String,
        val url: String,
        val playlistId: String,
        val folder: String,
    ) : Command()

    @Serializable
    @SerialName("make-folder")
    data class MakeFolder(
        override val id: String,
        override val at: String,
        val folder: String,
    ) : Command()

    @Serializable
    @SerialName("remove-folder")
    data class RemoveFolder(
        override val id: String,
        override val at: String,
        val folder: String,
    ) : Command()

    @Serializable
    @SerialName("delete-track")
    data class DeleteTrack(
        override val id: String,
        override val at: String,
        val folder: String,
        val videoId: String,
    ) : Command()

    @Serializable
    @SerialName("retry")
    data class Retry(
        override val id: String,
        override val at: String,
        val commandId: String,
    ) : Command()

    @Serializable
    @SerialName("set-library-root")
    data class SetLibraryRoot(
        override val id: String,
        override val at: String,
        val path: String,
    ) : Command()
}

@Serializable
data class CommandState(
    val status: ItemStatus,
    val error: String? = null,
)

@Serializable
data class StateTrack(
    val videoId: String,
    val title: String = "",
    val fileName: String? = null,
    val status: ItemStatus = ItemStatus.PENDING,
    val commandId: String? = null,
    val error: String? = null,
)

@Serializable
data class StateFolder(
    val name: String,
    val tracks: List<StateTrack> = emptyList(),
)

@Serializable
data class HistoryEntry(
    val at: String,
    val commandId: String,
    val status: String,
    val title: String = "",
    val folder: String = "",
    val videoId: String? = null,
    val error: String? = null,
)

@Serializable
data class LibraryState(
    val libraryName: String? = null,
    val libraryRoot: String? = null,
    val libraryReady: Boolean = false,
    val folders: List<StateFolder> = emptyList(),
    val commands: Map<String, CommandState> = emptyMap(),
)
