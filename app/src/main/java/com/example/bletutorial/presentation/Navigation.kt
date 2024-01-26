package com.example.bletutorial.presentation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

@Composable
fun Navigation(
    onBluetoothStateChanged:()->Unit
){
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = Screen.StartScreen.route){
        composable(Screen.StartScreen.route){
            StartScreen(navController = navController)
        }

        composable(Screen.DisplayScreen.route){
            DisplayScreen(onBluetoothStateChanged)
        }
    }
}

sealed class Screen(val route: String){
    object StartScreen:Screen("start_screen")
    object DisplayScreen:Screen("display_screen")
}