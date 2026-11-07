package com.engineerclient.util

import com.odtheking.odin.clickgui.settings.Setting
import com.odtheking.odin.features.Module

/**
 * One of this Odin module's settings, by the name the ClickGUI shows, as [T]; null when there is none by that name (an
 * Odin version renamed or dropped it) or it isn't a [T]. Reading another module's settings goes through its `settings`
 * map, which is the same in Odin 0.3.7 and 0.4.0, and a missing one has to leave the feature off, never crash it.
 */
inline fun <reified T : Setting<*>> Module.setting(name: String): T? = settings[name] as? T
