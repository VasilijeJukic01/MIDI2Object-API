package com.MIDI2Object.api.controller

import com.MIDI2Object.api.service.MidiService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.FileOutputStream

@RestController
@RequestMapping("/midi")
class MidiController(
    private val midiService: MidiService
) {

    @PostMapping("/upload")
    fun uploadMidi(@RequestParam("file") file: MultipartFile): ResponseEntity<Any> {
        val midiFile = File.createTempFile("received_midi", ".mid")

        FileOutputStream(midiFile).use { fos -> fos.write(file.bytes) }

        return try {
            val (rightHandNotes, leftHandNotes) = midiService.extractNotes(midiFile)
            midiFile.deleteOnExit()
            ResponseEntity(
                mapOf(
                    "success" to true,
                    "rightHandNotes" to rightHandNotes,
                    "leftHandNotes" to leftHandNotes
                ), HttpStatus.OK
            )
        } catch (e: Exception) {
            ResponseEntity(mapOf("success" to false, "message" to "Error processing MIDI file"), HttpStatus.INTERNAL_SERVER_ERROR)
        }
    }
}

