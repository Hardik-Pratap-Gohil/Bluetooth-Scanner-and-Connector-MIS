package com.example.bletutorial.data

import com.example.bletutorial.util.Resource
import kotlinx.coroutines.flow.MutableSharedFlow

interface ReceiveManager {
    val data: MutableSharedFlow<Resource<Result>>

    fun reconnect()

    fun disconnect()

    fun startReceiving()

    fun closeConnection()
}