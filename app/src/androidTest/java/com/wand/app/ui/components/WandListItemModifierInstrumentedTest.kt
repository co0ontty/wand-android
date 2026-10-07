package com.wand.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.SemanticsModifierNode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Foundation action semantics, not rendered-page touch/bounds or IME acceptance.
 * Compile without distribution/R8 flags. Run only after device-install authorization.
 */
@RunWith(AndroidJUnit4::class)
class WandListItemModifierInstrumentedTest {
    private fun semantics(modifier: Modifier): SemanticsConfiguration {
        val configuration = SemanticsConfiguration()
        modifier.foldIn(Unit) { _, element ->
            val node = (element as? ModifierNodeElement<*>)?.create()
            if (node is SemanticsModifierNode) with(node) { configuration.applySemantics() }
        }
        return configuration
    }

    @Test
    fun oneButtonSemanticActionInvokesOnlyOneCallback() {
        var calls = 0
        val action = semantics(Modifier.clickable(
            interactionSource = MutableInteractionSource(),
            indication = null,
            role = Role.Button,
            onClick = { calls++ },
        ))
        assertEquals(Role.Button, action[SemanticsProperties.Role])
        assertTrue(action[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(1, calls)
    }

    @Test
    fun controlledSwitchRowProducesOneToggleValue() {
        val values = mutableListOf<Boolean>()
        val row = semantics(Modifier.toggleable(
            value = false,
            interactionSource = MutableInteractionSource(),
            indication = null,
            role = Role.Switch,
            onValueChange = { values.add(it) },
        ))
        assertEquals(Role.Switch, row[SemanticsProperties.Role])
        assertEquals(ToggleableState.Off, row[SemanticsProperties.ToggleableState])
        assertTrue(row[SemanticsActions.OnClick].action!!.invoke())
        assertEquals(listOf(true), values)
    }

    @Test
    fun disabledRadioKeepsSelectedStateAndDisabledSemantics() {
        for (selected in listOf(false, true)) {
            var calls = 0
            val row = semantics(Modifier.selectable(
                selected = selected,
                enabled = false,
                interactionSource = MutableInteractionSource(),
                indication = null,
                role = Role.RadioButton,
                onClick = { calls++ },
            ))
            assertEquals(selected, row[SemanticsProperties.Selected])
            assertEquals(Role.RadioButton, row[SemanticsProperties.Role])
            assertTrue(row.contains(SemanticsProperties.Disabled))
            // Accessibility/pointer dispatch honors Disabled; do not manually bypass it.
            assertEquals(0, calls)
        }
    }
}
