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
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton

@Composable
fun PromptLibraryScreen(
    modifier: Modifier = Modifier
){
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)

    ) {
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
        ) {
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
        ) {
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
        ) {
            Column(
                modifier = Modifier.padding(16.dp)

            ) {
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
                ) {
                    Button(
                        onClick = { },
                    ) {
                        Text("Record now")
                    }
                    Button(
                        onClick = { },
                    ) {
                        Text("Save for later")
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Who taught you to cook?"
                    )

                    Text(
                        text = "Food • often opens into migration stories"
                    )
                }
                Button(
                    onClick = { },
                ) {
                    Text("+")
                }
            }
        }


    Spacer(modifier = Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth()
    ){
       Row(
           modifier = Modifier
               .fillMaxWidth()
               .padding(16.dp),
           horizontalArrangement = Arrangement.SpaceBetween
       ) {
           Column{
               Text(
                   text = "What did your street sound like at night?"
               )

               Text(
                   text = "Childhood • Sounds can unlock vivid memories"
               )
           }
           Button(
               onClick = { }
           ){
               Text("+")
           }
       }
    }
    Spacer(modifier = Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth()
    ){
       Row(
           modifier = Modifier
               .fillMaxWidth()
               .padding(16.dp),
           horizontalArrangement = Arrangement.SpaceBetween
       ) {
         Column{
             Text(
                 text = "What's a word your family used that nobody else did?"
             )

             Text(
                 text = "Childhood • Family language holds unique memories"
             )
         }
           Button(
               onClick = { }
           ){
               Text("+")
           }
       }
    }
    Spacer(modifier = Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth()
    ){
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ){
           Column{
               Text(
                   text = "Tell me about a day you'd live again."
               )

               Text(
                   text = "Reflection • Revisit a memory worth reliving"
               )
           }
            Button(
                onClick = { }
            ){
                Text("+")
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))

    OutlinedButton(
        onClick = { },
        modifier = Modifier.fillMaxWidth()
    ){
        Text("Write your own question")
    }
}
}



