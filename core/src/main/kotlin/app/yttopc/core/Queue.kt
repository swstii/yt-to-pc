package app.yttopc.core

import java.time.Instant
import java.util.UUID

sealed class QueueResult {
    data class Append(val command: Command) : QueueResult()
    data class Rejected(val reason: String) : QueueResult()
}

class Queue(
    commands: List<Command>,
    state: LibraryState,
    private val ids: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> String = { Instant.now().toString() },
) {
    val view: LibraryView = merge(commands, state)

    fun enqueue(sharedText: String, folder: String, choice: ShareChoice): QueueResult {
        val target = when (val parsed = YoutubeLinks.parse(sharedText)) {
            is ParseResult.Found -> parsed.target
            ParseResult.NotALink -> return reject("That share does not contain a link.")
            ParseResult.NotYoutube -> return reject("Share a YouTube song or playlist.")
        }
        val destination = folderNamed(folder) ?: return reject("Choose a folder first.")
        if (destination.removePending) return reject("That folder is waiting to be deleted.")

        return when (choice) {
            ShareChoice.SONG -> enqueueSong(target, destination)
            ShareChoice.PLAYLIST -> enqueueAlbum(target, destination)
        }
    }

    fun makeFolder(rawName: String): QueueResult {
        FolderNames.problem(rawName)?.let { return reject(it) }
        val name = FolderNames.cleaned(rawName)
        val existing = view.folders.find { FolderNames.same(it.name, name) }
        if (existing != null && existing.createError == null) {
            return reject("That folder already exists.")
        }
        if (existing?.removePending == true) return reject("That folder is waiting to be deleted.")
        return append(Command.MakeFolder(ids(), clock(), name))
    }

    fun removeFolder(name: String): QueueResult {
        val folder = folderNamed(name) ?: return reject("That folder is not in the queue.")
        if (!folder.canRemove) {
            return reject("That folder still has songs or a queued download.")
        }
        return append(Command.RemoveFolder(ids(), clock(), folder.name))
    }

    fun deleteTrack(folderName: String, videoId: String): QueueResult {
        val folder = folderNamed(folderName) ?: return reject("That folder is not in the queue.")
        val track = folder.tracks.find { it.videoId == videoId }
            ?: return reject("That song is not in this folder.")
        if (track.deletePending) return reject("That song is already waiting to be deleted.")
        return append(Command.DeleteTrack(ids(), clock(), folder.name, videoId))
    }

    fun retry(commandId: String): QueueResult {
        val failed = view.folders.any { folder ->
            folder.tracks.any { it.commandId == commandId && it.status == ItemStatus.FAILED && !it.deletePending } ||
                folder.albums.any { it.commandId == commandId && it.status == ItemStatus.FAILED }
        }
        if (!failed) return reject("There is nothing failed to retry.")
        return append(Command.Retry(ids(), clock(), commandId))
    }

    fun setLibraryRoot(rawPath: String): QueueResult {
        LibraryRoots.problem(rawPath)?.let { return reject(it) }
        val path = LibraryRoots.cleaned(rawPath)
        if (view.pendingLibraryRoot.equals(path, ignoreCase = true)) {
            return reject("That folder is already queued.")
        }
        if (view.pendingLibraryRoot == null && view.libraryRoot.equals(path, ignoreCase = true)) {
            return reject("The laptop is already using that folder.")
        }
        return append(Command.SetLibraryRoot(ids(), clock(), path))
    }

    private fun enqueueSong(target: YoutubeTarget, folder: FolderView): QueueResult {
        val videoId = when (target) {
            is YoutubeTarget.Video -> target.videoId
            is YoutubeTarget.VideoAndPlaylist -> target.videoId
            is YoutubeTarget.Playlist -> return reject("That link is a playlist. Save the whole playlist.")
        }
        val existing = folder.tracks.find { it.videoId == videoId }
        if (existing != null && existing.status == ItemStatus.FAILED && existing.commandId != null && !existing.deletePending) {
            return append(Command.Retry(ids(), clock(), existing.commandId))
        }
        if (existing != null && existing.blocksRemoval()) {
            return reject("That song is already in this folder.")
        }
        val url = target.url
        return append(Command.EnqueueVideo(ids(), clock(), url, videoId, folder.name))
    }

    private fun enqueueAlbum(target: YoutubeTarget, folder: FolderView): QueueResult {
        val playlistId = when (target) {
            is YoutubeTarget.Playlist -> target.playlistId
            is YoutubeTarget.VideoAndPlaylist -> target.playlistId
            is YoutubeTarget.Video -> return reject("That link is a single song.")
        }
        val existing = folder.albums.find { it.playlistId == playlistId }
        if (existing != null && existing.status == ItemStatus.FAILED) {
            return append(Command.Retry(ids(), clock(), existing.commandId))
        }
        if (existing != null) return reject("That playlist is already queued for this folder.")
        return append(Command.EnqueuePlaylist(ids(), clock(), target.url, playlistId, folder.name))
    }

    private fun folderNamed(name: String): FolderView? =
        view.folders.find { FolderNames.same(it.name, name) }

    private fun append(command: Command) = QueueResult.Append(command)

    private fun reject(reason: String) = QueueResult.Rejected(reason)
}
