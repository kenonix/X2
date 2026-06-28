import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.*
import kotlin.concurrent.thread

class AudioPlayer {
    private var currentClip: Clip? = null

    fun play(audioData: ByteArray, sampleRate: Int = 16000) {
        stop()
        thread {
            try {
                val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
                val bis = ByteArrayInputStream(audioData)
                val ais = AudioInputStream(bis, format, (audioData.size / 2).toLong())
                
                val clip = AudioSystem.getClip()
                currentClip = clip
                clip.open(ais)
                clip.start()
                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP) {
                        clip.close()
                        if (currentClip == clip) {
                            currentClip = null
                        }
                    }
                }
            } catch (e: Exception) {
                println("Playback error: ${e.message}")
            }
        }
    }

    fun playWav(file: File) {
        stop()
        thread {
            try {
                val ais = AudioSystem.getAudioInputStream(file)
                val clip = AudioSystem.getClip()
                currentClip = clip
                clip.open(ais)
                clip.start()
                clip.addLineListener { event ->
                    if (event.type == LineEvent.Type.STOP) {
                        clip.close()
                        if (currentClip == clip) {
                            currentClip = null
                        }
                    }
                }
            } catch (e: Exception) {
                println("WAV Playback error: ${e.message}")
            }
        }
    }

    fun stop() {
        try {
            currentClip?.let {
                if (it.isRunning) {
                    it.stop()
                }
                it.close()
            }
        } catch (e: Exception) {
            println("Stop playback error: ${e.message}")
        }
        currentClip = null
    }

    fun savePcmToWav(pcmData: ByteArray, sampleRate: Int, outputFile: File) {
        try {
            val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false)
            val bis = ByteArrayInputStream(pcmData)
            val ais = AudioInputStream(bis, format, (pcmData.size / 2).toLong())
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, outputFile)
            println("WAV saved successfully at ${outputFile.absolutePath}")
        } catch (e: Exception) {
            println("Save WAV error: ${e.message}")
        }
    }
}
