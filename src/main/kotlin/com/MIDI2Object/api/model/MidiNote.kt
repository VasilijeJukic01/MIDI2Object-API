package com.MIDI2Object.api.model

data class MidiNote(
    val pitch: Int,
    val startTime: Long,
    val duration: Long,
    val velocity: Int,
    val pedal: Boolean = false
)
