package com.example.myapplication.shared.processing

object SimpleLanguageDetector {
    fun detect(sections: List<TextSection>): String {
        var latinLetters = 0
        var cyrillicLetters = 0

        sections.asSequence()
            .flatMap { it.text.asSequence() }
            .filter(Char::isLetter)
            .take(MaxSampleLetters)
            .forEach { char ->
                when (char.lowercaseChar()) {
                    in 'a'..'z' -> latinLetters += 1
                    in '\u0430'..'\u044f', '\u0451' -> cyrillicLetters += 1
                }
            }

        return if (latinLetters > 0 && latinLetters >= cyrillicLetters) {
            DefaultAnalysisLanguage
        } else {
            UnknownLanguage
        }
    }

    const val UnknownLanguage: String = "unknown"

    private const val MaxSampleLetters = 4096
}
