package app.yttopc.core

data class TrackView(
    val videoId: String,
    val title: String,
    val fileName: String?,
    val status: ItemStatus,
    val commandId: String?,
    val error: String?,
    val deletePending: Boolean,
) {
    fun blocksRemoval(): Boolean = deletePending || status != ItemStatus.FAILED
}

data class AlbumView(
    val playlistId: String,
    val url: String,
    val commandId: String,
    val status: ItemStatus,
    val error: String?,
) {
    fun blocksRemoval(): Boolean = status != ItemStatus.FAILED
}

data class FolderView(
    val name: String,
    val createPending: Boolean,
    val createError: String?,
    val removePending: Boolean,
    val tracks: List<TrackView>,
    val albums: List<AlbumView>,
) {
    val canRemove: Boolean
        get() = !removePending && albums.none { it.blocksRemoval() } && tracks.none { it.blocksRemoval() }
}

data class LibraryView(
    val libraryRoot: String?,
    val pendingLibraryRoot: String?,
    val libraryReady: Boolean,
    val folders: List<FolderView>,
)

fun merge(commands: List<Command>, state: LibraryState): LibraryView {
    val builders = mutableListOf<FolderBuilder>()

    fun find(name: String) = builders.find { FolderNames.same(it.name, name) }

    fun folder(name: String): FolderBuilder {
        find(name)?.let { return it }
        return FolderBuilder(name).also { builders += it }
    }

    for (source in state.folders) {
        val builder = folder(source.name)
        builder.fromState = true
        builder.tracks += source.tracks.map { track ->
            TrackView(
                videoId = track.videoId,
                title = track.title.ifBlank { track.videoId },
                fileName = track.fileName,
                status = track.status,
                commandId = track.commandId,
                error = track.error,
                deletePending = false,
            )
        }
    }

    var pendingRoot: String? = null
    for (command in commands) {
        val recorded = state.commands[command.id]
        when (command) {
            is Command.MakeFolder -> applyMakeFolder(command, recorded, ::folder, ::find)
            is Command.RemoveFolder -> if (recorded == null) find(command.folder)?.removePending = true
            is Command.EnqueueVideo -> applyVideo(command, recorded, ::folder)
            is Command.EnqueuePlaylist -> applyPlaylist(command, recorded, ::folder)
            is Command.DeleteTrack -> if (recorded == null) {
                find(command.folder)?.tracks?.replaceAll { track ->
                    if (track.videoId == command.videoId) track.copy(deletePending = true) else track
                }
            }
            is Command.Retry -> if (recorded == null) applyRetry(command, builders)
            is Command.SetLibraryRoot -> if (recorded == null) pendingRoot = command.path
        }
    }

    val folders = builders
        .sortedBy { it.name.lowercase() }
        .map { it.toView() }
    return LibraryView(
        libraryRoot = state.libraryRoot,
        pendingLibraryRoot = pendingRoot,
        libraryReady = state.libraryReady && pendingRoot == null,
        folders = folders,
    )
}

private class FolderBuilder(val name: String) {
    var fromState: Boolean = false
    var createPending: Boolean = false
    var createError: String? = null
    var removePending: Boolean = false
    val tracks: MutableList<TrackView> = mutableListOf()
    val albums: MutableList<AlbumView> = mutableListOf()

    fun toView() = FolderView(
        name = name,
        createPending = createPending,
        createError = createError,
        removePending = removePending,
        tracks = tracks.toList(),
        albums = albums.toList(),
    )
}

private fun applyMakeFolder(
    command: Command.MakeFolder,
    recorded: CommandState?,
    folder: (String) -> FolderBuilder,
    find: (String) -> FolderBuilder?,
) {
    if (recorded == null) {
        val existing = find(command.folder)
        if (existing == null || !existing.fromState) {
            val created = existing ?: folder(command.folder)
            created.createPending = true
            created.createError = null
        }
        return
    }
    if (recorded.status == ItemStatus.FAILED && find(command.folder) == null) {
        val created = folder(command.folder)
        created.createPending = true
        created.createError = recorded.error
    }
}

private fun applyVideo(
    command: Command.EnqueueVideo,
    recorded: CommandState?,
    folder: (String) -> FolderBuilder,
) {
    val target = folder(command.folder)
    if (target.tracks.any { it.videoId == command.videoId || it.commandId == command.id }) return
    if (recorded == null) {
        target.tracks += queuedTrack(command, ItemStatus.PENDING, null)
    } else if (recorded.status == ItemStatus.FAILED) {
        target.tracks += queuedTrack(command, ItemStatus.FAILED, recorded.error)
    }
}

private fun queuedTrack(command: Command.EnqueueVideo, status: ItemStatus, error: String?) = TrackView(
    videoId = command.videoId,
    title = command.videoId,
    fileName = null,
    status = status,
    commandId = command.id,
    error = error,
    deletePending = false,
)

private fun applyPlaylist(
    command: Command.EnqueuePlaylist,
    recorded: CommandState?,
    folder: (String) -> FolderBuilder,
) {
    val target = folder(command.folder)
    if (target.albums.any { it.commandId == command.id }) return
    when {
        recorded == null -> target.albums += album(command, ItemStatus.PENDING, null)
        recorded.status == ItemStatus.FAILED -> target.albums += album(command, ItemStatus.FAILED, recorded.error)
    }
}

private fun album(command: Command.EnqueuePlaylist, status: ItemStatus, error: String?) = AlbumView(
    playlistId = command.playlistId,
    url = command.url,
    commandId = command.id,
    status = status,
    error = error,
)

private fun applyRetry(command: Command.Retry, builders: List<FolderBuilder>) {
    for (builder in builders) {
        val trackIndex = builder.tracks.indexOfFirst { it.commandId == command.commandId }
        if (trackIndex >= 0) {
            val track = builder.tracks[trackIndex]
            builder.tracks[trackIndex] = track.copy(status = ItemStatus.PENDING, error = null)
        }
        val albumIndex = builder.albums.indexOfFirst { it.commandId == command.commandId }
        if (albumIndex >= 0) {
            val album = builder.albums[albumIndex]
            builder.albums[albumIndex] = album.copy(status = ItemStatus.PENDING, error = null)
        }
    }
}
