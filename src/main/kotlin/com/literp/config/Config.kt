package com.literp.config

import java.io.FileInputStream
import java.util.*

class Config {
    val httpPort: Int

    init {
        val props = Properties()
        props.load(FileInputStream("env.properties"))

        httpPort = props.getProperty("http.port").toInt()
    }
}
