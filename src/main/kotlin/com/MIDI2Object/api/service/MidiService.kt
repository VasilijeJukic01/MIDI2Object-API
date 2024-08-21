package com.MIDI2Object.api.service

import com.MIDI2Object.api.model.MidiNote
import org.springframework.stereotype.Service
import java.io.File
import javax.sound.midi.*

@Service
class MidiService {

    /**
     * Extracts notes from a MIDI file and returns them as a pair of lists representing right-hand and left-hand notes.
     * It handles tempo changes, processes tracks, and adjusts for pauses between notes.
     *
     * @param midiFile  The MIDI file to process.
     * @return          A pair of lists containing right-hand and left-hand notes.
     */
    fun extractNotes(midiFile: File): Pair<List<MidiNote>, List<MidiNote>> {
        val sequence = MidiSystem.getSequence(midiFile)
        val resolution = sequence.resolution

        // Extract and Transform
        val tempoMap = extractTempoMap(sequence)
        val (rightHandTrack, leftHandTrack) = getTracks(sequence)

        val rightHandNotes = processTrack(rightHandTrack, resolution, tempoMap)
        val leftHandNotes = processTrack(leftHandTrack, resolution, tempoMap)

        return Pair(addPauses(rightHandNotes), addPauses(leftHandNotes))
    }

    /**
     * Extracts the tempo map from the MIDI sequence, identifying tempo changes and their ticks.
     *
     * @param sequence  The MIDI sequence to process.
     * @return          A list of pairs where each pair contains the tick position and the corresponding tempo.
     */
    private fun extractTempoMap(sequence: Sequence): List<Pair<Long, Int>> {
        val tempoMap = mutableListOf<Pair<Long, Int>>()
        val defaultTempo = 500000 // Default tempo in microseconds per quarter note (120 BPM)

        sequence.tracks.forEach { track ->
            for (i in 0 until track.size()) {
                val event = track.get(i)
                val message = event.message
                // Meta message for tempo change (0x51)
                if (message is MetaMessage && message.type == 0x51) {
                    val data = message.data
                    // The tempo is stored in three bytes, which need to be combined into a single integer.
                    val tempo = ((data[0].toInt() and 0xFF) shl 16) or
                            ((data[1].toInt() and 0xFF) shl 8) or
                            (data[2].toInt() and 0xFF)
                    tempoMap.add(event.tick to tempo)
                }
            }
        }

        // If no tempo changes are found -> add a default tempo at the beginning
        if (tempoMap.isEmpty()) {
            tempoMap.add(0L to defaultTempo)
        }
        return tempoMap
    }

    /**
     * Retrieves the tracks to be used for right-hand and left-hand notes. Assumes the first two tracks are used for this purpose.
     *
     * @param sequence                  The MIDI sequence containing the tracks.
     * @return                          A pair of tracks for the right and left hands.
     * @throws IllegalArgumentException if the MIDI file has fewer than two tracks.
     */
    private fun getTracks(sequence: Sequence): Pair<Track, Track> {
        if (sequence.tracks.size < 2) {
            throw IllegalArgumentException("MIDI file must contain at least two tracks")
        }
        return sequence.tracks[0] to sequence.tracks[1]
    }

    /**
     * Processes a track to extract note information, accounting for tempo changes, timing, and pedal states.
     *
     * @param track         The MIDI track to process.
     * @param resolution    The sequence resolution (ticks per beat).
     * @param tempoMap      The map of tempo changes and their corresponding ticks.
     * @return              A list of MidiNote objects representing the notes in the track.
     */
    private fun processTrack(
        track: Track,
        resolution: Int,
        tempoMap: List<Pair<Long, Int>>
    ): MutableList<MidiNote> {
        val notes = mutableListOf<MidiNote>()
        val noteOnEvents = mutableMapOf<Int, MidiNote>()
        var currentTimeInMilliseconds: Long = 0
        var lastTick = 0L
        var pedalPressed = false

        for (i in 0 until track.size()) {
            val event = track.get(i)
            val message = event.message

            currentTimeInMilliseconds += calculateTimeDelta(lastTick, event.tick, resolution, tempoMap)
            lastTick = event.tick

            if (message is ShortMessage) {
                handleMidiMessage(message, currentTimeInMilliseconds, pedalPressed, noteOnEvents, notes)

                // Handle pedal control change (sustain pedal)
                if (message.command == ShortMessage.CONTROL_CHANGE && message.data1 == 64) {
                    pedalPressed = message.data2 >= 64 // Pedal down if value >= 64
                }
            }
        }

        return notes
    }

    /**
     * Handles MIDI messages and updates note information accordingly.
     *
     * @param message                   The MIDI message.
     * @param currentTimeInMilliseconds The current time in milliseconds.
     * @param pedalPressed              Whether the sustain pedal is pressed.
     * @param noteOnEvents              A map of currently active note-on events.
     * @param notes                     The list of processed notes.
     */
    private fun handleMidiMessage(
        message: ShortMessage,
        currentTimeInMilliseconds: Long,
        pedalPressed: Boolean,
        noteOnEvents: MutableMap<Int, MidiNote>,
        notes: MutableList<MidiNote>
    ) {
        val pitch = message.data1
        val velocity = message.data2

        when (message.command) {
            ShortMessage.NOTE_ON -> {
                if (velocity > 0) { // Note-ON event
                    noteOnEvents[pitch] = MidiNote(
                        pitch = pitch,
                        startTime = currentTimeInMilliseconds,
                        duration = 0L,
                        velocity = velocity,
                        pedal = pedalPressed
                    )
                } else { // Note-OFF via NOTE_ON (velocity 0)
                    endNote(pitch, currentTimeInMilliseconds, noteOnEvents, notes)
                }
            }
            ShortMessage.NOTE_OFF -> endNote(pitch, currentTimeInMilliseconds, noteOnEvents, notes)
        }
    }

    /**
     * Ends a note by calculating its duration and adding it to the list of notes.
     *
     * @param pitch                     The pitch of the note.
     * @param currentTimeInMilliseconds The current time in milliseconds.
     * @param noteOnEvents              A map of currently active note-on events.
     * @param notes                     The list of processed notes.
     */
    private fun endNote(
        pitch: Int,
        currentTimeInMilliseconds: Long,
        noteOnEvents: MutableMap<Int, MidiNote>,
        notes: MutableList<MidiNote>
    ) {
        noteOnEvents[pitch]?.let { note ->
            val duration = currentTimeInMilliseconds - note.startTime
            if (duration > 0) {
                notes.add(note.copy(duration = duration))
            }
            noteOnEvents.remove(pitch)
        }
    }

    /**
     * Calculates the time delta between two ticks based on the resolution and tempo map.
     *
     * @param lastTick      The previous tick.
     * @param currentTick   The current tick.
     * @param resolution    The sequence resolution (ticks per beat).
     * @param tempoMap      The map of tempo changes and their corresponding ticks.
     * @return              The time delta in milliseconds.
     */
    private fun calculateTimeDelta(
        lastTick: Long,
        currentTick: Long,
        resolution: Int,
        tempoMap: List<Pair<Long, Int>>
    ): Long {
        var timeDelta = 0L
        var currentTempo = tempoMap.first().second
        var lastTempoTick = lastTick

        for ((tempoTick, tempoInMicroseconds) in tempoMap) {
            if (tempoTick >= currentTick) break
            timeDelta += ((tempoTick - lastTempoTick) * currentTempo) / (resolution * 1000)
            currentTempo = tempoInMicroseconds
            lastTempoTick = tempoTick
        }

        timeDelta += ((currentTick - lastTempoTick) * currentTempo) / (resolution * 1000)
        return timeDelta.coerceAtLeast(0)
    }

    /**
     * Adds pauses between notes where needed by creating "pause" notes with negative pitches.
     *
     * @param notes The list of processed notes.
     * @return      A new list of notes with pauses added.
     */
    private fun addPauses(notes: MutableList<MidiNote>): MutableList<MidiNote> {
        val adjustedNotes = mutableListOf<MidiNote>()

        if (notes.isNotEmpty() && notes.first().startTime > 0) {
            adjustedNotes.add(createPause(0L, notes.first().startTime))
        }

        for (i in 0 until notes.size - 1) {
            adjustedNotes.add(notes[i])

            val timeGap = notes[i + 1].startTime - (notes[i].startTime + notes[i].duration)
            if (timeGap > 0) {
                adjustedNotes.add(createPause(notes[i].startTime + notes[i].duration, timeGap))
            }
        }

        if (notes.isNotEmpty()) {
            adjustedNotes.add(notes.last())
        }

        return adjustedNotes
    }

    /**
     * Creates a "pause" note, represented by a MidiNote with a pitch of -1.
     *
     * @param startTime The start time of the pause.
     * @param duration  The duration of the pause.
     * @return          A MidiNote representing the pause.
     */
    private fun createPause(startTime: Long, duration: Long): MidiNote {
        return MidiNote(
            pitch = -1, // Pause indicator
            startTime = startTime,
            duration = duration,
            velocity = 0,
            pedal = false
        )
    }
}
