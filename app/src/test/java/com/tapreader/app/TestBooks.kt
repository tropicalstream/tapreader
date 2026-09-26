package com.tapreader.app

import java.io.File

/** The Gutenberg books in testbooks/ (gitignored; see testbooks/README in the test docs). */
object TestBooks {
    val root = File(System.getProperty("tapreader.root") ?: ".")
    val dir = File(root, "testbooks")

    fun files(): List<File> {
        val only = System.getProperty("tapreader.books").orEmpty().split(',').map { it.trim() }.filter { it.isNotBlank() }
        return (dir.listFiles()?.filter { DocumentParser.isSupported(it.name) } ?: emptyList())
            .filter { only.isEmpty() || it.nameWithoutExtension in only }
            .sortedBy { it.name }
    }

    fun load(f: File): Book = DocumentParser.parse(null, f).let { b ->
        // Gutenberg EPUB ids are all "hash of file name"; keep them distinct and readable.
        b.copy(id = "test_" + f.nameWithoutExtension)
    }

    fun text(b: Book, from: Int, to: Int) = b.words.subList(from, to.coerceAtMost(b.wordCount)).joinToString(" ") { it.text }
}
