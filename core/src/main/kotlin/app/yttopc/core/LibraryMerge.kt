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
    val title: String = "",
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

data class StatusItem(
    val at: String,
    val kind: String,
    val title: String,
    val folder: String,
    val videoId: String? = null,
)

data class LibraryView(
    val libraryRoot: String?,
    val pendingLibraryRoot: String?,
    val libraryReady: Boolean,
    val folders: List<FolderView>,
    val libraryName: String? = null,
    val activity: List<StatusItem> = emptyList(),
)

fun LibraryView.withKnownTitles(titles: Map<String, String>): LibraryView = copy(
    folders = folders.map { folder ->
        folder.copy(
            tracks = folder.tracks.map { track ->
                val better = titles[track.videoId]
                if (!better.isNullOrBlank() && (track.title.isBlank() || track.title == track.videoId)) {
                    track.copy(title = better)
                } else {
                    track
                }
            },
            albums = folder.albums.map { album ->
                val better = titles[album.playlistId]
                if (!better.isNullOrBlank() && album.title.isBlank()) album.copy(title = better) else album
            },
        )
    },
    activity = activity.map { item ->
        val better = item.videoId?.let { titles[it] }
        if (!better.isNullOrBlank() && (item.title.isBlank() || item.title == item.videoId)) {
            item.copy(title = better)
        } else {
            item
        }
    },
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
        libraryName = state.libraryName,
        activity = waitingWork(commands, state, builders),
    )
}

private fun waitingWork(commands: List<Command>, state: LibraryState, builders: List<FolderBuilder>): List<StatusItem> {
    val activity = mutableListOf<StatusItem>()
    val seen = mutableSetOf<String>()
    fun key(folder: String, id: String) = folder.lowercase() + "\n" + id
    for (command in commands) {
        val recorded = state.commands[command.id]
        val waiting = recorded == null || recorded.status == ItemStatus.PENDING || recorded.status == ItemStatus.DOWNLOADING
        if (!waiting) continue
        when (command) {
            is Command.EnqueueVideo -> {
                val track = builders.find { FolderNames.same(it.name, command.folder) }
                    ?.tracks?.find { it.videoId == command.videoId }
                val title = track?.title?.takeIf { it.isNotBlank() } ?: command.videoId
                val kind = if (track?.status == ItemStatus.DOWNLOADING) "Downloading" else "Download"
                activity += StatusItem(command.at, kind, title, command.folder, command.videoId)
                seen += key(command.folder, command.videoId)
            }
            is Command.EnqueuePlaylist -> {
                val album = builders.find { FolderNames.same(it.name, command.folder) }
                    ?.albums?.find { it.commandId == command.id }
                val title = album?.title?.takeIf { it.isNotBlank() } ?: "Playlist"
                activity += StatusItem(command.at, "Download", title, command.folder, command.playlistId)
            }
            is Command.MakeFolder -> activity += StatusItem(command.at, "Folder", command.folder, "")
            is Command.RemoveFolder -> activity += StatusItem(command.at, "Delete", command.folder, "")
            is Command.DeleteTrack -> {
                val track = builders.find { FolderNames.same(it.name, command.folder) }
                    ?.tracks?.find { it.videoId == command.videoId }
                val title = track?.title?.takeIf { it.isNotBlank() } ?: command.videoId
                activity += StatusItem(command.at, "Delete", title, command.folder, command.videoId)
                seen += key(command.folder, command.videoId)
            }
            is Command.Retry -> {
                val home = builders.find { builder ->
                    builder.tracks.any { it.commandId == command.commandId } ||
                        builder.albums.any { it.commandId == command.commandId }
                }
                val track = home?.tracks?.find { it.commandId == command.commandId }
                val album = home?.albums?.find { it.commandId == command.commandId }
                val title = track?.title?.takeIf { it.isNotBlank() }
                    ?: album?.title?.takeIf { it.isNotBlank() }
                    ?: "Retry"
                val videoId = track?.videoId ?: album?.playlistId
                activity += StatusItem(command.at, "Download", title, home?.name ?: "", videoId)
                if (home != null && track != null) seen += key(home.name, track.videoId)
            }
            is Command.SetLibraryRoot -> Unit
        }
    }
    for (builder in builders) {
        if (builder.createPending && activity.none { it.kind == "Folder" && it.title.equals(builder.name, ignoreCase = true) }) {
            activity += StatusItem("", "Folder", builder.name, "")
        }
        if (builder.removePending && activity.none { it.kind == "Delete" && it.folder.isEmpty() && it.title.equals(builder.name, ignoreCase = true) }) {
            activity += StatusItem("", "Delete", builder.name, "")
        }
        for (track in builder.tracks) {
            if (key(builder.name, track.videoId) in seen) continue
            when {
                track.deletePending -> activity += StatusItem("", "Delete", track.title, builder.name, track.videoId)
                track.status == ItemStatus.DOWNLOADING -> activity += StatusItem("", "Downloading", track.title, builder.name, track.videoId)
                track.status == ItemStatus.PENDING -> activity += StatusItem("", "Download", track.title, builder.name, track.videoId)
            }
        }
    }
    return activity
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
