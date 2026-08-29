package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * The home screen: recents, granted folders, and the first-run explainer.
 *
 * The folder read normally happens on a background thread, which would make
 * every assertion here a race. [directExecutor] runs it inline instead, so a
 * test asserts on a screen that has finished drawing.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        context.getSharedPreferences("recents", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.contentResolver.persistedUriPermissions.forEach {
            context.contentResolver.releasePersistableUriPermission(
                it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    /** Runs everything submitted to it on the calling thread. */
    private fun directExecutor(): ExecutorService {
        val direct = Executor { it.run() }
        return object : ExecutorService, Executor by direct {
            override fun shutdown() = Unit
            override fun shutdownNow() = emptyList<Runnable>()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
            override fun <T : Any?> submit(task: java.util.concurrent.Callable<T>) =
                throw UnsupportedOperationException()
            override fun <T : Any?> submit(task: Runnable, result: T) =
                throw UnsupportedOperationException()
            override fun submit(task: Runnable) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAll(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>
            ) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAll(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>,
                timeout: Long,
                unit: TimeUnit
            ) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAny(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>
            ) = throw UnsupportedOperationException()
            override fun <T : Any?> invokeAny(
                tasks: MutableCollection<out java.util.concurrent.Callable<T>>,
                timeout: Long,
                unit: TimeUnit
            ) = throw UnsupportedOperationException()
        }
    }

    private fun home(state: Bundle? = null): ActivityController<MainActivity> {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        controller.get().loader = directExecutor()
        return controller.create(state).start().resume().visible().also {
            shadowOf(context.mainLooper).idle()
        }
    }

    private fun ActivityController<MainActivity>.list(): RecyclerView =
        get().findViewById(R.id.list)

    private fun ActivityController<MainActivity>.welcome(): View =
        get().findViewById(R.id.welcome)

    /** Every row's title, read off the adapter through a bound holder. */
    private fun ActivityController<MainActivity>.rowTitles(): List<String> {
        val rv = list()
        val adapter = rv.adapter!!
        return (0 until adapter.itemCount).mapNotNull { position ->
            val type = adapter.getItemViewType(position)
            val holder = adapter.createViewHolder(rv, type)
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.title)?.text?.toString()
                ?: holder.itemView.findViewById<TextView>(R.id.headerText)?.text?.toString()
        }
    }

    private fun granted(fixture: String, name: String) {
        val uri = FixtureProvider.uriFor(fixture)
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        Recents.add(context, uri, name)
    }

    // ---------------------------------------------------------------
    // First run
    // ---------------------------------------------------------------

    /**
     * Nothing opened and no folder granted, so there is nothing to list. The
     * explainer takes the whole screen rather than an empty list taking it.
     */
    @Test
    fun aFirstRunShowsTheExplainerInsteadOfAnEmptyList() {
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.VISIBLE)
        assertThat(controller.list().visibility).isEqualTo(View.GONE)
    }

    @Test
    fun theExplainerShowsEveryKindGanderOpens() {
        val grid = home().get().findViewById<ViewGroup>(R.id.formatGrid)
        assertThat(grid.childCount).isEqualTo(WELCOME_BADGES.size)
        val labels = (0 until grid.childCount)
            .map { (grid.getChildAt(it) as TextView).text.toString() }
        assertThat(labels).containsExactlyElementsIn(WELCOME_BADGES.map { it.first }).inOrder()
    }

    /**
     * TalkBack reads one sentence for the grid rather than walking nine tiles
     * that mean nothing one at a time.
     */
    @Test
    fun theExplainerGridIsOneStopForAScreenReader() {
        val grid = home().get().findViewById<ViewGroup>(R.id.formatGrid)
        assertThat(grid.contentDescription.toString())
            .isEqualTo(context.getString(R.string.welcome_formats_spoken))
    }

    @Test
    fun theExplainerGoesOnceSomethingHasBeenOpened() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()
        assertThat(controller.welcome().visibility).isEqualTo(View.GONE)
        assertThat(controller.list().visibility).isEqualTo(View.VISIBLE)
    }

    // ---------------------------------------------------------------
    // Recents
    // ---------------------------------------------------------------

    @Test
    fun recentsAreListedNewestFirstUnderTheirOwnHeading() {
        granted("six-pages.pdf", "Alder Court.pdf")
        granted("budget.xlsx", "Q3 Budget.xlsx")

        val titles = home().rowTitles()

        assertThat(titles).contains(context.getString(R.string.recent_files))
        assertThat(titles.filter { it.contains('.') })
            .containsExactly("Q3 Budget.xlsx", "Alder Court.pdf").inOrder()
    }

    @Test
    fun aRecentWhoseGrantIsGoneDropsOffTheScreen() {
        granted("six-pages.pdf", "Alder Court.pdf")
        granted("budget.xlsx", "Q3 Budget.xlsx")
        context.contentResolver.releasePersistableUriPermission(
            FixtureProvider.uriFor("budget.xlsx"), Intent.FLAG_GRANT_READ_URI_PERMISSION
        )

        val titles = home().rowTitles()

        assertThat(titles).contains("Alder Court.pdf")
        assertThat(titles).doesNotContain("Q3 Budget.xlsx")
    }

    /** Tapping a recent opens it in the viewer, with a read grant attached. */
    @Test
    fun tappingARecentOpensItInTheViewer() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val controller = home()
        val rv = controller.list()
        val adapter = rv.adapter!!

        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.title)?.text?.toString() ==
                "Alder Court.pdf"
        }
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()

        val started = shadowOf(controller.get()).nextStartedActivity
        assertThat(started.component!!.className).isEqualTo(ViewerActivity::class.java.name)
        assertThat(started.data.toString()).contains("six-pages.pdf")
        assertThat(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    }

    // ---------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------

    @Test
    fun aPhoneListsInOneColumn() {
        val manager = home().list().layoutManager as GridLayoutManager
        assertThat(manager.spanCount).isEqualTo(1)
    }

    /**
     * A tablet gets two columns of files, which is the whole of the tablet
     * layout: one integer overridden in values-sw600dp.
     */
    @Test
    @Config(qualifiers = "sw600dp")
    fun aTabletListsInTwoColumns() {
        val manager = home().list().layoutManager as GridLayoutManager
        assertThat(manager.spanCount).isEqualTo(2)
    }

    /**
     * Headings span the full width whatever the column count, and so does an
     * out-of-range position: the layout manager asks about positions mid-update
     * and a cell-shaped guess would throw where a heading-shaped one reflows.
     */
    @Test
    @Config(qualifiers = "sw600dp")
    fun headingsAndOutOfRangePositionsSpanTheWholeWidth() {
        granted("six-pages.pdf", "Alder Court.pdf")
        val manager = home().list().layoutManager as GridLayoutManager
        // position 0 is the Recents heading
        assertThat(manager.spanSizeLookup.getSpanSize(0)).isEqualTo(2)
        assertThat(manager.spanSizeLookup.getSpanSize(9999)).isEqualTo(2)
    }

    // ---------------------------------------------------------------
    // State across a rotation
    // ---------------------------------------------------------------

    /** Grants a folder holding one subfolder and two files, and answers its tree URI. */
    private fun grantedFolder(): android.net.Uri {
        FakeDocumentsProvider.install()
            .folder(
                "root", "Documents",
                ChildDoc("sub", "Leases", MIME_DIR, 0, 0),
                ChildDoc("f1", "zeta.pdf", "application/pdf", 2048, 0),
                ChildDoc("f2", "alpha.pdf", "application/pdf", 1024, 0),
                ChildDoc("f3", ".hidden.pdf", "application/pdf", 512, 0),
            )
        val tree = FakeDocumentsProvider.treeUri()
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        return tree
    }

    /** Clicks the first row whose title is [title]. */
    private fun ActivityController<MainActivity>.clickRow(title: String) {
        val rv = list()
        val adapter = rv.adapter!!
        val position = (0 until adapter.itemCount).first { position ->
            val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
            adapter.bindViewHolder(holder, position)
            holder.itemView.findViewById<TextView>(R.id.title)?.text?.toString() == title
        }
        val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        holder.itemView.performClick()
        shadowOf(context.mainLooper).idle()
    }

    @Test
    fun aGrantedFolderIsListedUnderItsProviderGivenName() {
        grantedFolder()
        val titles = home().rowTitles()
        assertThat(titles).contains(context.getString(R.string.folders))
        assertThat(titles).contains("Documents")
    }

    /** Directories first, then files by name, and dotfiles nowhere. */
    @Test
    fun openingAFolderListsItInOrderWithDotfilesHidden() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")

        val titles = controller.rowTitles()

        assertThat(titles).containsAtLeast("Leases", "alpha.pdf", "zeta.pdf").inOrder()
        assertThat(titles).doesNotContain(".hidden.pdf")
    }

    /** Inside a folder the toolbar names it, and the wordmark stands down. */
    @Test
    fun theToolbarNamesTheFolderYouAreIn() {
        grantedFolder()
        val controller = home()
        val toolbar = controller.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.isNullOrEmpty()).isTrue()

        controller.clickRow("Documents")

        assertThat(toolbar.title.toString()).isEqualTo("Documents")
        assertThat(controller.get().findViewById<View>(R.id.lockup).visibility)
            .isEqualTo(View.GONE)
    }

    @Test
    fun backComesOutOfAFolder() {
        grantedFolder()
        val controller = home()
        controller.clickRow("Documents")

        controller.get().onBackPressedDispatcher.onBackPressed()
        shadowOf(context.mainLooper).idle()

        assertThat(controller.rowTitles()).contains(context.getString(R.string.folders))
    }

    /**
     * A tablet is rotated constantly, and losing your place three folders deep
     * on every turn is where this was found.
     */
    @Test
    fun theFolderYouAreInSurvivesARotation() {
        grantedFolder()
        val first = home()
        first.clickRow("Documents")

        val state = Bundle()
        first.saveInstanceState(state)
        assertThat(state.getStringArrayList("stack.treeUris")).hasSize(1)
        assertThat(state.getStringArrayList("stack.labels")).containsExactly("Documents")

        val second = home(state)

        val toolbar = second.get()
            .findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        assertThat(toolbar.title.toString()).isEqualTo("Documents")
        assertThat(second.rowTitles()).contains("alpha.pdf")
    }

    /**
     * A truncated bundle would index out of bounds while restoring three
     * parallel lists. Landing at the root is where a failure would land anyway.
     */
    @Test
    fun aTruncatedBundleLandsAtTheRootRatherThanCrashing() {
        val damaged = Bundle().apply {
            putStringArrayList("stack.treeUris", arrayListOf("content://a/tree/1", "content://a/tree/2"))
            putStringArrayList("stack.docIds", arrayListOf("1"))
            putStringArrayList("stack.labels", arrayListOf("One", "Two"))
        }
        val controller = home(damaged)
        assertThat(controller.welcome().visibility).isEqualTo(View.VISIBLE)
    }

    // ---------------------------------------------------------------
    // About
    // ---------------------------------------------------------------

    /**
     * The About screen asks Android what the app requests and prints the
     * answer, rather than printing a claim. This is the assertion that the
     * answer is still "none".
     */
    @Test
    fun aboutReportsNoPermissionsBecauseThereAreNone() {
        val controller = home()
        controller.get().findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
            .menu.performIdentifierAction(R.id.action_about, 0)
        shadowOf(context.mainLooper).idle()

        val dialog = shadowOf(org.robolectric.shadows.ShadowDialog.getLatestDialog()).let {
            org.robolectric.shadows.ShadowDialog.getLatestDialog()
        }
        assertThat(dialog).isNotNull()
        val text = dialog.findViewById<TextView>(R.id.aboutPermissions).text.toString()
        assertThat(text).isEqualTo(context.getString(R.string.about_permissions_none))
    }
}
