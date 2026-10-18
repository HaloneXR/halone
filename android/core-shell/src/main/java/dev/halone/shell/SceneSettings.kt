package dev.halone.shell

import dev.halone.compositor.CollisionPolicy
import dev.halone.compositor.DockVisibility
import dev.halone.compositor.PanelScene

/** Gives [scene] what the settings say about following, collisions, the dock and spacing. */
fun ShellSettings.applyTo(scene: PanelScene<*>) {
    scene.followsHead = boolean(ShellSettings.FOLLOW_ENABLED)
    scene.followMarginDegrees = number(ShellSettings.FOLLOW_MARGIN_DEGREES).toFloat()
    scene.catchUpSeconds = number(ShellSettings.FOLLOW_CATCH_UP_SECONDS).toFloat()
    scene.collisionPolicy = when (string(ShellSettings.COLLISION_POLICY)) {
        "overlap" -> CollisionPolicy.OVERLAP
        "avoid-pinned" -> CollisionPolicy.AVOID_PINNED
        else -> CollisionPolicy.AVOID_ALL
    }
    scene.dockVisibility = if (string(ShellSettings.DOCK_VISIBILITY) == "auto-hide") DockVisibility.AUTO_HIDE else DockVisibility.ALWAYS
    scene.windowsHideDelaySeconds = number(ShellSettings.WINDOWS_AUTO_HIDE_DELAY_SECONDS).toFloat()
    val spacing = number(ShellSettings.SLOT_SPACING_DEGREES).toFloat()
    if (spacing != scene.slotSpacingDegrees) scene.layOutCylindrical(spacing, immediate = false)
}
