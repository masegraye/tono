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

    /**
     * Two modules that each have a file of the same name in the same package compile to
     * classes of the same name, and a packaged app holds only one of them: the other's
     * functions are then missing when it runs. Each module builds and tests fine alone, so
     * this looks across them.
     */
    @Test
    fun `no class of this module has a namesake in another`() {
        val anchor = Class.forName("org.example.kotlintest.RexAppKt")
        val root = File(anchor.protectionDomain.codeSource.location.toURI())
        val files = if (root.isDirectory) {
            root.walkTopDown().filter { it.extension == "class" }.map { it.relativeTo(root).path.replace(File.separatorChar, '/') }.toList()
        } else {
            java.util.zip.ZipFile(root).use { zip -> zip.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList() }
        }
        val shared = files.filter { anchor.classLoader.getResources(it).toList().size > 1 }
        assertTrue(shared.isEmpty(), "also defined by another module: $shared")
    }
}

