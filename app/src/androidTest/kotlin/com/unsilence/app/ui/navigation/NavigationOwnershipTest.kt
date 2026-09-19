package com.unsilence.app.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.unsilence.app.ui.onboarding.SessionContent
import com.unsilence.app.ui.shared.ResumedEffect
import com.unsilence.app.ui.shared.PendingEdits
import com.unsilence.app.ui.shared.rememberWriteAwareDismiss
import com.unsilence.app.ui.common.LocalShowSnackbar
import kotlinx.coroutines.CompletableDeferred
import com.unsilence.app.ui.theme.UnsilenceTheme
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Account-free structural tests. These do not simulate social actions or certify gesture feel. */
@RunWith(AndroidJUnit4::class)
class NavigationOwnershipTest {
    @get:Rule val compose = createComposeRule()

    private class Probe(val savedState: SavedStateHandle) : ViewModel() {
        val edits = PendingEdits(viewModelScope)
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private class Registry {
        val models = mutableMapOf<String, Probe>()
        val composed = mutableSetOf<String>()
        val resumed = mutableSetOf<String>()
    }

    @Composable
    private fun EntryProbe(id: String, registry: Registry) {
        val vm = viewModel { Probe(createSavedStateHandle()) }
        SideEffect { registry.models[id] = vm }
        DisposableEffect(id) {
            registry.composed += id
            onDispose { registry.composed -= id }
        }
        ResumedEffect(id) {
            registry.resumed += id
            try { awaitCancellation() } finally { registry.resumed -= id }
        }
        var value by rememberSaveable { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Text(id)
            BasicTextField(value, { value = it }, Modifier.testTag("draft-$id"))
        }
    }

    @Test fun coveredEntriesLeaveCompositionKeepStateAndClearOnlyWhenPopped() {
        val registry = Registry()
        lateinit var navigator: AppNavigator
        compose.setContent {
            UnsilenceTheme {
                SessionContent("alice-0") {
                    val nav = rememberAppNavigator("alice-0")
                    SideEffect { navigator = nav }
                    AppNavDisplay(nav) { EntryProbe(it.id, registry) }
                }
            }
        }
        compose.waitForIdle()
        val root = compose.runOnIdle { navigator.stateKey }
        compose.onNodeWithTag("draft-$root").performTextReplacement("feed-position-marker")
        val rootVm = compose.runOnIdle { registry.models.getValue(root) }
        val child = compose.runOnIdle {
            navigator.push(AppDestination.Settings)
            (navigator.backStack.last() as AppEntry).id
        }
        compose.waitForIdle()
        val childVm = compose.runOnIdle {
            assertEquals(setOf(child), registry.composed)
            assertEquals(setOf(child), registry.resumed)
            assertFalse(rootVm.cleared)
            registry.models.getValue(child)
        }
        compose.runOnIdle { navigator.pop() }
        compose.waitForIdle()
        compose.onNodeWithTag("draft-$root").assertTextEquals("feed-position-marker")
        compose.runOnIdle {
            assertSame(rootVm, registry.models.getValue(root))
            assertFalse(rootVm.cleared)
            assertTrue(childVm.cleared)
            assertEquals(setOf(root), registry.resumed)
        }
    }

    @Test fun replacedAccountClearsShellAndEveryNestedEntryIncludingCoveredOnes() {
        val registry = Registry()
        val session = mutableStateOf<String?>("alice-0")
        lateinit var navigator: AppNavigator
        compose.setContent {
            UnsilenceTheme {
                SessionContent(session.value) {
                    val account = session.value
                    if (account == null) Text("Signed out") else {
                        val shellVm = viewModel<Probe>(key = "shell") { Probe(createSavedStateHandle()) }
                        SideEffect { registry.models["shell-$account"] = shellVm }
                        val nav = rememberAppNavigator(account)
                        SideEffect { navigator = nav }
                        AppNavDisplay(nav) { EntryProbe(it.id, registry) }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { navigator.push(AppDestination.Article("one")) }
        compose.waitForIdle()
        compose.runOnIdle { navigator.push(AppDestination.Profile("author")) }
        compose.waitForIdle()
        val old = compose.runOnIdle { registry.models.values.toList() }
        assertEquals(4, old.size)
        compose.runOnIdle { session.value = null }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(old.all { it.cleared })
            assertTrue(registry.composed.isEmpty())
            assertTrue(registry.resumed.isEmpty())
        }
        // Same account, new login: not a stale Activity-owned VM.
        compose.runOnIdle { session.value = "alice-1" }
        compose.waitForIdle()
        compose.runOnIdle {
            assertFalse(registry.models.getValue("shell-alice-1").cleared)
            assertNotSame(registry.models["shell-alice-0"], registry.models["shell-alice-1"])
            assertEquals(1, navigator.backStack.size)
        }
    }

    @Test fun savedBackStackAndFieldsRestoreWithoutReplacingTheSessionStore() {
        val registry = Registry()
        val restoration = StateRestorationTester(compose)
        lateinit var navigator: AppNavigator
        restoration.setContent {
            UnsilenceTheme {
                SessionContent("alice-0") {
                    val nav = rememberAppNavigator("alice-0")
                    SideEffect { navigator = nav }
                    AppNavDisplay(nav) { EntryProbe(it.id, registry) }
                }
            }
        }
        compose.waitForIdle()
        val child = compose.runOnIdle {
            navigator.push(AppDestination.EditProfile)
            (navigator.backStack.last() as AppEntry).id
        }
        compose.waitForIdle()
        compose.onNodeWithTag("draft-$child").performTextReplacement("unsaved form")
        val before = compose.runOnIdle {
            registry.models.getValue(child).also { it.savedState["marker"] = "entry-scoped" }
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        compose.onNodeWithTag("draft-$child").assertTextEquals("unsaved form")
        compose.runOnIdle {
            assertEquals(child, (navigator.backStack.last() as AppEntry).id)
            assertSame(before, registry.models.getValue(child))
            assertEquals("entry-scoped", before.savedState.get<String>("marker"))
            assertFalse(before.cleared)
        }
    }

    @Test fun closeWaitsForAnAlreadyStartedWriteThenClearsItsEntry() {
        val completion = CompletableDeferred<Unit>()
        val notices = mutableListOf<String>()
        lateinit var navigator: AppNavigator
        lateinit var editor: Probe
        compose.setContent {
            UnsilenceTheme {
                CompositionLocalProvider(LocalShowSnackbar provides { notices += it }) {
                    val nav = rememberAppNavigator("test")
                    SideEffect { navigator = nav }
                    AppNavDisplay(nav) { entry ->
                        if (entry.destination == AppDestination.Tabs) Text("Root") else {
                            val vm = viewModel { Probe(createSavedStateHandle()) }
                            SideEffect { editor = vm }
                            val dismiss = rememberWriteAwareDismiss(vm.edits, true, nav::pop)
                            TextButton(onClick = dismiss, modifier = Modifier.testTag("close-editor")) {
                                Text("Close")
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { navigator.push(AppDestination.Settings) }
        compose.waitForIdle()
        compose.runOnIdle { editor.edits.launch { completion.await() } }
        compose.waitForIdle()
        compose.onNodeWithTag("close-editor").performClick()
        compose.runOnIdle {
            assertEquals(2, navigator.backStack.size)
            assertFalse(editor.cleared)
            assertEquals(listOf("Finishing changes…"), notices)
        }
        compose.runOnIdle { completion.complete(Unit) }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, navigator.backStack.size)
            assertTrue(editor.cleared)
        }
    }
}
