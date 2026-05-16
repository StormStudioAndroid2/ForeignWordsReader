package com.example.myapplication.shared.processing

class ConlluParser {
    fun parse(sectionId: String, conllu: String): List<AnalyzedToken> {
        val tokens = mutableListOf<AnalyzedToken>()
        var tokenOrder = 0L

        conllu.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) {
                return@forEach
            }

            val columns = line.split('\t')
            if (columns.size < ConlluColumnCount || !columns[0].isRegularTokenId()) {
                return@forEach
            }

            val surface = columns[1]
            val lemma = columns[2]
            val upos = columns[3]
            tokens += AnalyzedToken(
                sectionId = sectionId,
                tokenOrder = tokenOrder,
                surface = surface,
                lemma = lemma,
                upos = upos,
                tokenType = tokenType(upos = upos, surface = surface, lemma = lemma),
            )
            tokenOrder += 1
        }

        return tokens
    }

    private fun String.isRegularTokenId(): Boolean =
        all(Char::isDigit)

    private fun tokenType(upos: String, surface: String, lemma: String): TokenType =
        when (upos) {
            "PUNCT" -> TokenType.Punctuation
            "SYM" -> TokenType.Symbol
            else -> if (surface.hasLetterOrDigit() || lemma.hasLetterOrDigit()) TokenType.Word else TokenType.Other
        }

    private fun String.hasLetterOrDigit(): Boolean =
        any(Char::isLetterOrDigit)

    private companion object {
        const val ConlluColumnCount = 10
    }
}
