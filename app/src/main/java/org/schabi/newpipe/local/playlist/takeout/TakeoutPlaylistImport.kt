package org.schabi.newpipe.local.playlist.takeout

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Observable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.schedulers.Schedulers
import io.reactivex.rxjava3.subjects.BehaviorSubject
import java.util.Optional
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.schabi.newpipe.NewPipeDatabase
import org.schabi.newpipe.R
import org.schabi.newpipe.database.stream.model.StreamEntity
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.local.playlist.LocalPlaylistManager
import org.schabi.newpipe.util.ExtractorHelper

/**
 * Imports Takeout playlists as local playlists. Video details (title, channel, thumbnail) are
 * loaded one by one through the privacy tunnel, like opening each video; no account is involved.
 *
 * Runs at app level, so leaving the settings screen does not stop it.
 */
object TakeoutPlaylistImport {
    private const val CONCURRENT_REQUESTS = 3

    sealed interface State {
        data object Idle : State
        data class Running(val done: Int, val total: Int) : State
        data class Finished(val result: Result) : State
        data class Failed(val error: Throwable) : State
    }

    data class Result(
        val playlists: Int,
        val videos: Int,
        val unavailable: Int,
        val alreadyPresent: Int
    )

    private val state = BehaviorSubject.createDefault<State>(State.Idle)

    fun state(): Observable<State> = state

    fun isRunning(): Boolean = state.value is State.Running

    fun start(context: Context, uri: Uri) {
        if (isRunning()) {
            return
        }
        val app = context.applicationContext
        state.onNext(State.Running(0, 0))
        Single.fromCallable { import(app, uri) }
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .subscribe(
                { result ->
                    state.onNext(State.Finished(result))
                    val message = if (result.playlists == 0 && result.alreadyPresent == 0) {
                        app.getString(R.string.takeout_playlists_import_none)
                    } else {
                        app.getString(
                            R.string.takeout_playlists_import_done,
                            result.playlists,
                            result.videos,
                            result.unavailable,
                            result.alreadyPresent
                        )
                    }
                    Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                },
                { error ->
                    state.onNext(State.Failed(error))
                    Toast.makeText(
                        app,
                        app.getString(R.string.takeout_playlists_import_failed, error.message),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
    }

    private fun import(context: Context, uri: Uri): Result {
        val playlists = context.contentResolver.openInputStream(uri)?.use {
            TakeoutPlaylistParser.parse(it, displayName(context, uri))
        } ?: throw IllegalStateException("cannot open $uri")

        val manager = LocalPlaylistManager(NewPipeDatabase.getInstance(context))
        val existing = manager.playlists.blockingFirst().mapNotNull { it.orderingName }.toHashSet()
        val toImport = playlists.filter { it.name !in existing }

        val total = toImport.sumOf { it.videoIds.size }
        val done = AtomicInteger()
        val firstError = AtomicReference<Throwable>()
        state.onNext(State.Running(0, total))

        var imported = 0
        var videos = 0
        for (playlist in toImport) {
            val streams = Flowable.fromIterable(playlist.videoIds)
                // keeps the playlist order while loading a few videos at a time
                .concatMapEager({ id -> streamOf(id, firstError).toFlowable() }, CONCURRENT_REQUESTS, 1)
                .doOnNext { state.onNext(State.Running(done.incrementAndGet(), total)) }
                .toList()
                .blockingGet()
                .mapNotNull { it.orElse(null) }
            if (streams.isNotEmpty()) {
                manager.createPlaylist(playlist.name, streams).blockingGet()
                imported++
                videos += streams.size
            }
        }

        if (total > 0 && videos == 0) {
            // nothing could be loaded (offline, tunnel down...): report why
            firstError.get()?.let { throw it }
        }
        return Result(imported, videos, total - videos, playlists.size - toImport.size)
    }

    private fun streamOf(
        videoId: String,
        firstError: AtomicReference<Throwable>
    ): Single<Optional<StreamEntity>> = ExtractorHelper.getStreamInfo(
        ServiceList.YouTube.serviceId,
        "https://www.youtube.com/watch?v=$videoId",
        false
    )
        .map { Optional.of(StreamEntity(it)) }
        // deleted or private videos are skipped
        .onErrorReturn {
            firstError.compareAndSet(null, it)
            Optional.empty()
        }

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment
}
