package com.paperlight.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.paperlight.reader.data.BookDatabase
import com.paperlight.reader.data.BookRepository
import com.paperlight.reader.ui.LibraryViewModel
import com.paperlight.reader.ui.PaperlightApp
import com.paperlight.reader.ui.theme.PaperlightTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = BookDatabase.getInstance(applicationContext)
        val repository = BookRepository(applicationContext, database.bookDao())

        setContent {
            val libraryViewModel: LibraryViewModel = viewModel(
                factory = LibraryViewModel.factory(repository, applicationContext)
            )
            PaperlightTheme {
                PaperlightApp(viewModel = libraryViewModel)
            }
        }
    }
}
