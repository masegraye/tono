package org.example.kotlintest

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
