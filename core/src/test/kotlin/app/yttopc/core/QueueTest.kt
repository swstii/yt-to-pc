package app.yttopc.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class YoutubeLinksTest {
    @Test
    fun songLink() {
        val parsed = YoutubeLinks.parse("Listen https://www.youtube.com/watch?v=dQw4w9WgXcQ now")
        val found = assertIs<ParseResult.Found>(parsed)
        val video = assertIs<YoutubeTarget.Video>(found.target)
        assertEquals("dQw4w9WgXcQ", video.videoId)
    }

    @Test
    fun shortLink() {
        val parsed = YoutubeLinks.parse("https://youtu.be/dQw4w9WgXcQ")
        val video = assertIs<YoutubeTarget.Video>(assertIs<ParseResult.Found>(parsed).target)
        assertEquals("dQw4w9WgXcQ", video.videoId)
    }

    @Test
    fun playlistLink() {
        val parsed = YoutubeLinks.parse("https://www.youtube.com/playlist?list=PLabcdefghijklmnop")
        val playlist = assertIs<YoutubeTarget.Playlist>(assertIs<ParseResult.Found>(parsed).target)
        assertEquals("PLabcdefghijklmnop", playlist.playlistId)
    }

    @Test
    fun songInsidePlaylistOffersBoth() {
        val parsed = YoutubeLinks.parse("https://music.youtube.com/watch?v=dQw4w9WgXcQ&list=PLabcdefghijklmnop")
        val both = assertIs<YoutubeTarget.VideoAndPlaylist>(assertIs<ParseResult.Found>(parsed).target)
        assertEquals("dQw4w9WgXcQ", both.videoId)
        assertEquals("PLabcdefghijklmnop", both.playlistId)
    }

    @Test
    fun otherSitesAreRejected() {
        assertIs<ParseResult.NotYoutube>(YoutubeLinks.parse("https://example.com/watch?v=dQw4w9WgXcQ"))
        assertIs<ParseResult.NotALink>(YoutubeLinks.parse("not a link"))
    }
}

class FolderAndRootTest {
    @Test
    fun folderRules() {
        assertNull(FolderNames.problem("pryda prydz"))
        assertTrue(FolderNames.problem("bad:name") != null)
        assertTrue(FolderNames.problem("_queue") != null)
        assertTrue(FolderNames.problem("CON") != null)
        assertTrue(FolderNames.same("Pryda", "pryda"))
    }

    @Test
    fun libraryRootRules() {
        assertNull(LibraryRoots.problem("E:\\Music"))
        assertNull(LibraryRoots.problem("E:\\"))
        assertTrue(LibraryRoots.problem("Music") != null)
        assertTrue(LibraryRoots.problem("E:\\Music\\..\\Other") != null)
    }
}

class NotebookJsonTest {
    @Test
    fun commandRoundTrip() {
        val command = Command.EnqueueVideo("1", "t", "https://youtu.be/dQw4w9WgXcQ", "dQw4w9WgXcQ", "pryda prydz")
        val parsed = NotebookJson.readCommands(NotebookJson.commandLine(command))
        assertEquals(listOf(command), parsed)
    }

    @Test
    fun skipsBrokenCommandLines() {
        val text = """
            {"type":"make-folder","id":"1","at":"t","folder":"Opus"}
            this is not json
            {"type":"make-folder","id":"2","at":"t","folder":"Other"}
        """.trimIndent()
        assertEquals(listOf("Opus", "Other"), NotebookJson.readCommands(text).map { (it as Command.MakeFolder).folder })
    }

    @Test
    fun stateRoundTripKeepsUnknownStatus() {
        val state = LibraryState(
            libraryRoot = "E:\\Music",
            libraryReady = true,
            folders = listOf(
                StateFolder(
                    "pryda prydz",
                    listOf(StateTrack("dQw4w9WgXcQ", "Generate", "Generate.mp3", ItemStatus.DONE, "1")),
                ),
            ),
            commands = mapOf("1" to CommandState(ItemStatus.DONE)),
        )
        val raw = """
            {"libraryRoot":"E:\\Music","libraryReady":true,"folders":[{"name":"pryda prydz","tracks":[{"videoId":"dQw4w9WgXcQ","title":"Generate","fileName":"Generate.mp3","status":"done","commandId":"1"}]}],"commands":{"1":{"status":"done"}}}
        """.trimIndent()
        assertEquals(state.libraryRoot, NotebookJson.readState(raw).libraryRoot)
        assertEquals(ItemStatus.DONE, NotebookJson.readState(raw).folders.single().tracks.single().status)
        val unknown = raw.replace("\"done\"", "\"later\"")
        assertEquals(ItemStatus.UNKNOWN, NotebookJson.readState(unknown).folders.single().tracks.single().status)
    }
}

class QueueTest {
    private var ids = 0
    private var ticks = 0

    @Test
    fun queuesASongIntoAPendingFolder() {
        val queue = queue()
        val made = queue.makeFolder("pryda prydz").command()
        val withFolder = queue(listOf(made))
        val queued = withFolder.enqueue(SONG, "pryda prydz", ShareChoice.SONG).command()
        val video = assertIs<Command.EnqueueVideo>(queued)
        assertEquals("dQw4w9WgXcQ", video.videoId)
        assertEquals("pryda prydz", video.folder)
        val view = queue(listOf(made, queued)).view
        assertEquals(ItemStatus.PENDING, view.folders.single().tracks.single().status)
    }

    @Test
    fun rejectsADuplicateInTheSameFolder() {
        val state = LibraryState(
            folders = listOf(StateFolder("pryda prydz", listOf(StateTrack("dQw4w9WgXcQ", "Generate", status = ItemStatus.DONE)))),
        )
        val result = queue(state = state).enqueue(SONG, "Pryda Prydz", ShareChoice.SONG)
        assertEquals("That song is already in this folder.", result.reason())
    }

    @Test
    fun allowsTheSameSongInAnotherFolder() {
        val state = LibraryState(
            folders = listOf(
                StateFolder("pryda prydz", listOf(StateTrack("dQw4w9WgXcQ", status = ItemStatus.DONE))),
                StateFolder("other"),
            ),
        )
        assertIs<Command.EnqueueVideo>(queue(state = state).enqueue(SONG, "other", ShareChoice.SONG).command())
    }

    @Test
    fun failedSongIsRetriedInsteadOfQueuedTwice() {
        val failed = Command.EnqueueVideo("song-1", "t", SONG, "dQw4w9WgXcQ", "pryda prydz")
        val state = LibraryState(commands = mapOf("song-1" to CommandState(ItemStatus.FAILED, "offline")))
        val result = queue(listOf(failed), state).enqueue(SONG, "pryda prydz", ShareChoice.SONG)
        val retry = assertIs<Command.Retry>(result.command())
        assertEquals("song-1", retry.commandId)
        val view = queue(listOf(failed, retry), state).view
        assertEquals(ItemStatus.PENDING, view.folders.single().tracks.single().status)
        assertEquals(1, view.folders.single().tracks.size)
    }

    @Test
    fun playlistBecomesAnAlbumUntilTheLaptopExpandsIt() {
        val queue = queue(state = LibraryState(folders = listOf(StateFolder("Opus"))))
        val command = assertIs<Command.EnqueuePlaylist>(queue.enqueue(ALBUM, "Opus", ShareChoice.PLAYLIST).command())
        val pending = queue(listOf(command), LibraryState(folders = listOf(StateFolder("Opus")))).view
        assertEquals("PLabcdefghijklmnop", pending.folders.single().albums.single().playlistId)
        val expanded = LibraryState(
            folders = listOf(
                StateFolder(
                    "Opus",
                    listOf(StateTrack("dQw4w9WgXcQ", "Generate", status = ItemStatus.PENDING, commandId = "child")),
                ),
            ),
            commands = mapOf(command.id to CommandState(ItemStatus.EXPANDED)),
        )
        val done = queue(listOf(command), expanded).view.folders.single()
        assertTrue(done.albums.isEmpty())
        assertEquals("Generate", done.tracks.single().title)
    }

    @Test
    fun secondCopyOfAWaitingPlaylistIsRejected() {
        val command = Command.EnqueuePlaylist("album-1", "t", ALBUM, "PLabcdefghijklmnop", "Opus")
        val result = queue(
            listOf(command),
            LibraryState(folders = listOf(StateFolder("Opus"))),
        ).enqueue(ALBUM, "Opus", ShareChoice.PLAYLIST)
        assertEquals("That playlist is already queued for this folder.", result.reason())
    }

    @Test
    fun emptyFolderCanBeRemovedAndAFilledFolderCannot() {
        val empty = queue(state = LibraryState(folders = listOf(StateFolder("Opus"))))
        assertIs<Command.RemoveFolder>(empty.removeFolder("Opus").command())
        val filled = LibraryState(
            folders = listOf(StateFolder("Opus", listOf(StateTrack("dQw4w9WgXcQ", status = ItemStatus.DONE)))),
        )
        assertEquals("That folder still has songs or a queued download.", queue(state = filled).removeFolder("Opus").reason())
    }

    @Test
    fun deletingASongStaysPendingUntilTheLaptopConfirms() {
        val state = LibraryState(
            folders = listOf(StateFolder("Opus", listOf(StateTrack("dQw4w9WgXcQ", "Generate", status = ItemStatus.DONE)))),
        )
        val command = assertIs<Command.DeleteTrack>(queue(state = state).deleteTrack("Opus", "dQw4w9WgXcQ").command())
        val view = queue(listOf(command), state).view.folders.single()
        assertTrue(view.tracks.single().deletePending)
        assertTrue(!view.canRemove)
    }

    @Test
    fun libraryRootIsQueuedUntilTheLaptopConfirmsIt() {
        val command = assertIs<Command.SetLibraryRoot>(queue().setLibraryRoot("E:\\Music").command())
        val view = queue(listOf(command)).view
        assertEquals("E:\\Music", view.pendingLibraryRoot)
        assertTrue(!view.libraryReady)
        val ready = LibraryState(
            libraryRoot = "E:\\Music",
            libraryReady = true,
            commands = mapOf(command.id to CommandState(ItemStatus.DONE)),
        )
        val confirmed = queue(listOf(command), ready).view
        assertNull(confirmed.pendingLibraryRoot)
        assertTrue(confirmed.libraryReady)
    }

    private fun queue(commands: List<Command> = emptyList(), state: LibraryState = LibraryState()) = Queue(
        commands,
        state,
        ids = { "id-${++ids}" },
        clock = { "2026-11-06T00:00:${(++ticks).toString().padStart(2, '0')}Z" },
    )

    private fun QueueResult.command(): Command = (this as QueueResult.Append).command

    private fun QueueResult.reason(): String = (this as QueueResult.Rejected).reason

    private companion object {
        const val SONG = "https://youtu.be/dQw4w9WgXcQ"
        const val ALBUM = "https://www.youtube.com/playlist?list=PLabcdefghijklmnop"
    }
}
