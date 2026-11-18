package org.example.kotlintest

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class ClassesLoadTest {
    /**
     * Loads every class of the app. The compiler can report success and still write a class
     * the JVM refuses, which otherwise shows only when the app starts: an early return out of
     * nested composable blocks once left a marker behind that no class may contain.
     */
    @Test
    fun `every class the compiler wrote can be loaded`() {
        val anchor = Class.forName("org.example.kotlintest.RexAppKt")
        val root = File(anchor.protectionDomain.codeSource.location.toURI())
        val names = if (root.isDirectory) {
            root.walkTopDown().filter { it.extension == "class" }.map { it.relativeTo(root).path }.toList()
        } else {
            java.util.zip.ZipFile(root).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList() }
        }.map { it.removeSuffix(".class").replace(File.separatorChar, '.').replace('/', '.') }.filter { it.startsWith("org.example.kotlintest.") }
        assertTrue(names.size > 20, "found only ${names.size} classes under $root")
        for (name in names) Class.forName(name, false, anchor.classLoader).declaredMethods // linking is what fails
    }
}
