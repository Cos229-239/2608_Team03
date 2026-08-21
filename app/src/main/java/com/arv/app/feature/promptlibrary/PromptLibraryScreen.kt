package com.arv.app.feature.promptlibrary

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.material3.Button

@Composable
fun PromptLibraryScreen(
    modifier: Modifier = Modifier
){
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ){
        Text(
            text = "Questions to ask",
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "For Ruth Delaney",
            style = MaterialTheme.typography.titleLarge
        )

        Text(
            text = "Age 83 • 11 recordings • last recorded 2 days ago",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ){
        FilterChip(
            selected = true,
            onClick = { },
            label = {
                Text("Suggested")
            }
        )
            FilterChip(
                selected = false,
                onClick = { },
                label = {
                    Text("Childhood")
                }
            )

            FilterChip(
                selected = false,
                onClick = { },
                label = {
                    Text("Food")
                }
            )

           FilterChip(
               selected = false,
               onClick = { },
               label = {
                   Text("Work")
               }
           )

        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ){
          FilterChip(
              selected = false,
              onClick = { },
              label = {
                  Text("Hard things")
              }
          )
            FilterChip(
                selected = false,
                onClick = { },
                label = {
                    Text("Faith")
                }
            )
        }
       Card(
           modifier = Modifier.fillMaxWidth()
       ){
         Column(
             modifier = Modifier.padding(16.dp)

         ){
             Text(
                 text = "WHY THIS ONE"
             )

             Text(
                 text = "You mentioned a song your mother hummed. Can you try to sing it ?"
             )

             Text(
                 text = "Ruth referenced this at 06:18 in \"Sunday kitchen \" but never sang it. Melodies are the first thing lost"

             )

             Row(
                 horizontalArrangement = Arrangement.spacedBy(8.dp)
             ){
                 Button(
                    onClick = { },
             ){
                 Text("Record now")
             }
                 Button(
                     onClick = { },
                 ){
                     Text("Save for later")
                 }
             }
         }
       }

    }
}




