package com.engineerclient.util

import com.odtheking.odin.clickgui.settings.Setting
import com.odtheking.odin.features.Module

inline fun <reified T : Setting<*>> Module.setting(name: String): T? = settings[name] as? T
